package com.fuyouxuan.mq;

import cn.hutool.json.JSONUtil;
import com.fuyouxuan.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Receives the five-minute payment timeout and closes only still-unpaid orders. */
@Slf4j
@Component
public class SeckillOrderTimeoutConsumer {

    @Resource
    private Environment env;

    @Resource
    private IVoucherOrderService voucherOrderService;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() throws MQClientException {
        boolean consumerEnabled = env.getProperty("seckill.consumer.enabled", Boolean.class, true);
        if (!consumerEnabled) {
            return;
        }
        consumer = new DefaultMQPushConsumer("seckill-order-timeout-consumer-group");
        consumer.setNamesrvAddr(env.getRequiredProperty("rocketmq.name-server"));
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe("seckill-order-timeout-topic", "*");
        consumer.setConsumeThreadMin(4);
        consumer.setConsumeThreadMax(8);
        consumer.setMaxReconsumeTimes(16);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> consume(messages));
        consumer.start();
        log.info("Seckill payment-timeout consumer started");
    }

    ConsumeConcurrentlyStatus consume(List<MessageExt> messages) {
        try {
            for (MessageExt message : messages) {
                SeckillOrderMessage order = JSONUtil.toBean(
                        new String(message.getBody(), StandardCharsets.UTF_8),
                        SeckillOrderMessage.class);
                voucherOrderService.closeExpiredOrder(
                        order.getOrderId(), order.getUserId(), order.getVoucherId());
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception e) {
            log.error("Close expired seckill order failed", e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
