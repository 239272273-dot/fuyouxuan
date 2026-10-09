package com.fuyouxuan.mq;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_QUEUE_KEY;
import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_QUEUE_LOCK_KEY;

@Component
public class SeckillOrderProducer {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderProducer.class);

    private static final String TOPIC = "seckill-order-topic";
    private static final int PARTITIONS = 16;
    private static final int BATCH_SIZE = 50;
    private static final int MAX_SEND_RETRY = 3;
    private static final long FLUSH_INTERVAL_MS = 100;
    private static final long FLUSH_LOCK_TTL_SECONDS = 30;
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final DateTimeFormatter ID_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Resource
    private Environment env;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private DefaultMQProducer producer;

    private ScheduledExecutorService flushScheduler;

    private static final DefaultRedisScript<String> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(String.class);

        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) " +
                        "else return 0 end");
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    public void init() {
        String nameServer = env.getRequiredProperty("rocketmq.name-server");
        try {
            producer = new DefaultMQProducer("seckill-producer-group");
            producer.setNamesrvAddr(nameServer);
            producer.setSendMsgTimeout(3000);
            producer.setRetryTimesWhenSendFailed(0);
            producer.setRetryAnotherBrokerWhenNotStoreOK(true);
            producer.start();
            log.info("RocketMQ producer started, nameServer={}", nameServer);
        } catch (Exception e) {
            log.error("RocketMQ producer start failed, queued orders will be compensated if sending keeps failing", e);
        }

        flushScheduler = Executors.newScheduledThreadPool(PARTITIONS, r -> {
            Thread t = new Thread(r, "seckill-redis-list-flush");
            t.setDaemon(false);
            return t;
        });

        for (int i = 0; i < PARTITIONS; i++) {
            final int partition = i;
            flushScheduler.scheduleWithFixedDelay(
                    () -> flushPendingOrders(partition),
                    FLUSH_INTERVAL_MS,
                    FLUSH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
        }
        log.info("Seckill Redis List flush workers started, partitions={}, batchSize={}, interval={}ms",
                PARTITIONS, BATCH_SIZE, FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void destroy() {
        if (flushScheduler != null) {
            flushScheduler.shutdown();
            try {
                flushScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < PARTITIONS; i++) {
            flushPendingOrders(i);
        }
        if (producer != null) {
            producer.shutdown();
        }
        log.info("Seckill producer closed");
    }

    public Long sendOrderMessage(Long voucherId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        String date = now.format(ID_DATE_FORMATTER);

        String result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(timestamp),
                date,
                String.valueOf(PARTITIONS)
        );

        String scriptResult = Objects.requireNonNull(result, "seckill lua result is null");
        if (scriptResult.startsWith("0:")) {
            return Long.valueOf(scriptResult.substring(2));
        }
        log.warn("Seckill rejected, code={}, voucherId={}, userId={}", scriptResult, voucherId, userId);
        return null;
    }

    void flushPendingOrders(int partition) {
        String queueKey = SECKILL_ORDER_QUEUE_KEY + partition;
        String lockKey = SECKILL_ORDER_QUEUE_LOCK_KEY + partition;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey, lockValue, FLUSH_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        try {
            List<String> payloads = stringRedisTemplate.opsForList().range(queueKey, 0, BATCH_SIZE - 1);
            if (CollUtil.isEmpty(payloads)) {
                return;
            }

            if (sendBatchWithRetry(payloads)) {
                ackBatch(queueKey, payloads.size());
                return;
            }

            log.error("RocketMQ batch send failed after {} retries, partition={}, count={}",
                    MAX_SEND_RETRY, partition, payloads.size());
            // Keep the batch in Redis. A later scheduled pass retries it.
        } catch (Exception e) {
            log.error("Flush seckill order queue failed, partition={}", partition, e);
        } finally {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        }
    }

    boolean sendBatchWithRetry(List<String> payloads) {
        List<Message> messages = buildMessages(payloads);
        for (int attempt = 1; attempt <= MAX_SEND_RETRY; attempt++) {
            try {
                if (producer == null) {
                    throw new IllegalStateException("RocketMQ producer is not started");
                }
                SendResult result = producer.send(messages);
                if (result != null && SendStatus.SEND_OK.equals(result.getSendStatus())) {
                    log.debug("RocketMQ batch send success, count={}, msgId={}", messages.size(), result.getMsgId());
                    return true;
                }
                log.warn("RocketMQ batch send returned non-ok status, attempt={}, result={}", attempt, result);
            } catch (Exception e) {
                log.warn("RocketMQ batch send failed, attempt={}, count={}", attempt, messages.size(), e);
            }
            sleepBeforeRetry(attempt);
        }
        return false;
    }

    private List<Message> buildMessages(List<String> payloads) {
        List<Message> messages = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            SeckillOrderMessage orderMessage = JSONUtil.toBean(payload, SeckillOrderMessage.class);
            Message message = new Message(TOPIC, payload.getBytes(StandardCharsets.UTF_8));
            message.setKeys(String.valueOf(orderMessage.getOrderId()));
            messages.add(message);
        }
        return messages;
    }

    private void ackBatch(String queueKey, int count) {
        stringRedisTemplate.opsForList().trim(queueKey, count, -1);
        log.debug("Acked Redis order queue batch, queueKey={}, count={}", queueKey, count);
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(100L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
