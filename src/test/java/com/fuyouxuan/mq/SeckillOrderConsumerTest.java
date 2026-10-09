package com.fuyouxuan.mq;

import com.fuyouxuan.entity.VoucherOrder;
import com.fuyouxuan.service.IVoucherOrderService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillOrderConsumerTest {

    private IVoucherOrderService orderService;
    private ValueOperations<String, String> valueOperations;
    private SeckillOrderConsumer consumer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        orderService = mock(IVoucherOrderService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        consumer = new SeckillOrderConsumer();
        ReflectionTestUtils.setField(consumer, "voucherOrderService", orderService);
        ReflectionTestUtils.setField(consumer, "stringRedisTemplate", redisTemplate);
    }

    @Test
    void duplicateKeyIsTreatedAsSuccessfulIdempotentConsumption() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(orderService).createVoucherOrder(any(VoucherOrder.class));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(
                Collections.singletonList(message(101L, 7L, 9L)));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        verify(valueOperations).set(SECKILL_ORDER_STATUS_KEY + 101L, "SUCCESS", 1, TimeUnit.DAYS);
    }

    @Test
    void nonDuplicateFailureRequestsRocketMqRetry() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(orderService).createVoucherOrder(any(VoucherOrder.class));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(
                Collections.singletonList(message(102L, 7L, 9L)));

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, status);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void successfulInsertMarksOrderSuccessful() {
        ConsumeConcurrentlyStatus status = consumer.consumeMessages(
                Collections.singletonList(message(103L, 8L, 10L)));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        verify(orderService).createVoucherOrder(argThat(order ->
                order.getId().equals(103L)
                        && order.getUserId().equals(8L)
                        && order.getVoucherId().equals(10L)));
        verify(valueOperations).set(SECKILL_ORDER_STATUS_KEY + 103L, "SUCCESS", 1, TimeUnit.DAYS);
    }

    private MessageExt message(long orderId, long userId, long voucherId) {
        MessageExt message = new MessageExt();
        String json = "{\"orderId\":" + orderId
                + ",\"userId\":" + userId
                + ",\"voucherId\":" + voucherId + "}";
        message.setBody(json.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
