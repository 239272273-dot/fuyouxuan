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

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() throws MQClientException {
        String nameServer = env.getRequiredProperty("rocketmq.name-server");
        consumer = new DefaultMQPushConsumer("seckill-order-consumer-group");
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe("seckill-order-topic", "*");
        consumer.setConsumeThreadMin(8);
        consumer.setConsumeThreadMax(16);
        consumer.setConsumeMessageBatchMaxSize(10);
        consumer.setPullBatchSize(32);
        consumer.setMaxReconsumeTimes(16);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> consumeMessages(msgs));
        consumer.start();
        log.info("RocketMQ消费者启动成功, nameServer={}", nameServer);
    }

    ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> msgs) {
        for (MessageExt msg : msgs) {
            try {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                SeckillOrderMessage message = JSONUtil.toBean(body, SeckillOrderMessage.class);

                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(message.getOrderId());
                voucherOrder.setUserId(message.getUserId());
                voucherOrder.setVoucherId(message.getVoucherId());

                try {
                    voucherOrderService.createVoucherOrder(voucherOrder);
                } catch (DuplicateKeyException e) {
                    // 主键或用户-券唯一索引冲突，说明该业务订单已经落库。
                    log.warn("重复订单消息已跳过, orderId={}, userId={}, voucherId={}",
                            message.getOrderId(), message.getUserId(), message.getVoucherId());
                }

                stringRedisTemplate.opsForValue().set(
                        SECKILL_ORDER_STATUS_KEY + message.getOrderId(),
                        "SUCCESS", 1, TimeUnit.DAYS);
                log.info("订单消费成功, orderId={}", message.getOrderId());
            } catch (Exception e) {
                log.error("订单消费失败", e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
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
