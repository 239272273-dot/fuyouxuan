package com.fuyouxuan.mq;

import cn.hutool.json.JSONUtil;
import com.fuyouxuan.entity.VoucherOrder;
import com.fuyouxuan.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;

@Slf4j
@Component
public class SeckillOrderConsumer {

    @Resource
    private Environment env;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() throws MQClientException {
        boolean consumerEnabled = env.getProperty("seckill.consumer.enabled", Boolean.class, true);
        if (!consumerEnabled) {
            log.info("Seckill RocketMQ consumer is disabled on instance={}",
                    env.getProperty("INSTANCE_NAME", "unknown"));
            return;
        }

        String nameServer = env.getRequiredProperty("rocketmq.name-server");
        consumer = new DefaultMQPushConsumer("seckill-order-consumer-group");
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe("seckill-order-topic", "*");
        consumer.setConsumeThreadMin(8);
        consumer.setConsumeThreadMax(16);
        consumer.setConsumeMessageBatchMaxSize(32);
        consumer.setPullBatchSize(64);
        consumer.setMaxReconsumeTimes(16);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> consumeMessages(msgs));
        consumer.start();
        log.info("RocketMQ消费者启动成功, nameServer={}", nameServer);
    }

    ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> msgs) {
        try {
            List<VoucherOrder> orders = new ArrayList<>(msgs.size());
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                SeckillOrderMessage message = JSONUtil.toBean(body, SeckillOrderMessage.class);

                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(message.getOrderId());
                voucherOrder.setUserId(message.getUserId());
                voucherOrder.setVoucherId(message.getVoucherId());
                orders.add(voucherOrder);
            }

            // One transaction per RocketMQ delivery batch. INSERT IGNORE keeps
            // primary-key and user-voucher idempotency, while stock is updated
            // once per voucher instead of once per message.
            voucherOrderService.createVoucherOrders(orders);
            for (int i = 0; i < orders.size(); i++) {
                VoucherOrder order = orders.get(i);
                SeckillOrderMessage orderMessage = JSONUtil.toBean(
                        new String(msgs.get(i).getBody(), StandardCharsets.UTF_8),
                        SeckillOrderMessage.class);
                seckillOrderProducer.sendOrderTimeoutMessage(orderMessage);
                stringRedisTemplate.opsForValue().set(
                        SECKILL_ORDER_STATUS_KEY + order.getId(),
                        "UNPAID", 1, TimeUnit.DAYS);
            }
            log.debug("订单批量消费成功, count={}", orders.size());
        } catch (Exception e) {
            log.error("订单批量消费失败, count={}", msgs.size(), e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
