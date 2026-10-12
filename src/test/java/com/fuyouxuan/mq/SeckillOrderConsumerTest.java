package com.fuyouxuan.mq;

import com.fuyouxuan.entity.VoucherOrder;
import com.fuyouxuan.service.IVoucherOrderService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillOrderConsumerTest {

    private IVoucherOrderService orderService;
    private ValueOperations<String, String> valueOperations;
    private SeckillOrderProducer producer;
    private SeckillOrderConsumer consumer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        orderService = mock(IVoucherOrderService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        producer = mock(SeckillOrderProducer.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        consumer = new SeckillOrderConsumer();
        ReflectionTestUtils.setField(consumer, "voucherOrderService", orderService);
        ReflectionTestUtils.setField(consumer, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "seckillOrderProducer", producer);
    }

    @Test
    void nonDuplicateFailureRequestsRocketMqRetry() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(orderService).createVoucherOrders(anyList());

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(
                Collections.singletonList(message(102L, 7L, 9L)));

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, status);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void successfulInsertMarksOrderUnpaidAndSchedulesTimeout() {
        ConsumeConcurrentlyStatus status = consumer.consumeMessages(
                Collections.singletonList(message(103L, 8L, 10L)));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        verify(orderService).createVoucherOrders(argThat(orders -> {
            VoucherOrder order = orders.get(0);
            return order.getId().equals(103L)
                    && order.getUserId().equals(8L)
                    && order.getVoucherId().equals(10L);
        }));
        verify(producer).sendOrderTimeoutMessage(argThat(order -> order.getOrderId().equals(103L)));
        verify(valueOperations).set(SECKILL_ORDER_STATUS_KEY + 103L, "UNPAID", 1, TimeUnit.DAYS);
    }

    @Test
    void multipleMessagesUseOneDatabaseBatchAndMarkEveryOrderSuccessful() {
        List<MessageExt> messages = java.util.Arrays.asList(
                message(104L, 9L, 10L),
                message(105L, 10L, 10L));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(messages);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        verify(orderService, times(1)).createVoucherOrders(argThat(orders -> orders.size() == 2));
        verify(producer, times(2)).sendOrderTimeoutMessage(any(SeckillOrderMessage.class));
        verify(valueOperations).set(SECKILL_ORDER_STATUS_KEY + 104L, "UNPAID", 1, TimeUnit.DAYS);
        verify(valueOperations).set(SECKILL_ORDER_STATUS_KEY + 105L, "UNPAID", 1, TimeUnit.DAYS);
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
