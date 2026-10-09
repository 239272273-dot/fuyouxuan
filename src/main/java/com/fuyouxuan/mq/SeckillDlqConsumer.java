package com.fuyouxuan.mq;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 死信队列消费者：重试 16 次全败后，补偿 Redis
 * <p>
 * 流程：
 * 1. 原消费者重试 16 次 MySQL 写入均失败
 * 2. 消息进入 %DLQ%seckill-order-consumer-group
 * 3. 本消费者运行 compensate_seckill.lua 恢复 Redis
 * 4. 记录日志 + 告警（可接入钉钉/邮件）
 */
@Slf4j
@Component
public class SeckillDlqConsumer {

    @Resource
    private Environment env;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private DefaultMQPushConsumer consumer;

    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;

    static {
        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("compensate_seckill.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    public void init() throws MQClientException {
        String nameServer = env.getRequiredProperty("rocketmq.name-server");
        // RocketMQ DLQ 命名规则：%DLQ% + 原 consumer group
        consumer = new DefaultMQPushConsumer("%DLQ%seckill-order-consumer-group-handler");
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe("%DLQ%seckill-order-consumer-group", "*");
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(3);
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                    org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext context) {
                for (MessageExt msg : msgs) {
                    try {
                        String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                        SeckillOrderMessage message = JSONUtil.toBean(body, SeckillOrderMessage.class);

                        log.error("死信消息: 16次重试均失败, orderId={}, userId={}, voucherId={}",
                                message.getOrderId(), message.getUserId(), message.getVoucherId());

                        // 最终补偿：恢复 Redis 库存
                        stringRedisTemplate.execute(COMPENSATE_SCRIPT,
                                Collections.emptyList(),
                                message.getVoucherId().toString(),
                                message.getUserId().toString(),
                                message.getOrderId().toString());

                        // TODO: 接入告警（钉钉/邮件/企业微信）
                        log.error("已补偿Redis，需人工排查MySQL, orderId={}", message.getOrderId());
                    } catch (Exception e) {
                        log.error("死信补偿失败, msg={}", msg, e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
        log.info("秒杀死信队列消费者启动成功");
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
