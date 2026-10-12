package com.fuyouxuan.mq;

import com.fuyouxuan.service.IVoucherOrderService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SeckillOrderTimeoutConsumerTest {

    private IVoucherOrderService orderService;
    private SeckillOrderTimeoutConsumer consumer;

    @BeforeEach
    void setUp() {
        orderService = mock(IVoucherOrderService.class);
        consumer = new SeckillOrderTimeoutConsumer();
        ReflectionTestUtils.setField(consumer, "voucherOrderService", orderService);
    }

    @Test
    void timeoutClosesTheMatchingUnpaidOrder() {
        ConsumeConcurrentlyStatus status = consumer.consume(Collections.singletonList(message()));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        verify(orderService).closeExpiredOrder(501L, 41L, 8L);
    }

    @Test
    void failureRequestsRocketMqRetry() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(orderService).closeExpiredOrder(501L, 41L, 8L);

        ConsumeConcurrentlyStatus status = consumer.consume(Collections.singletonList(message()));

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, status);
    }

    private MessageExt message() {
        MessageExt message = new MessageExt();
        message.setBody("{\"orderId\":501,\"userId\":41,\"voucherId\":8}"
                .getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
