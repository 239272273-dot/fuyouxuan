package com.fuyouxuan.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_QUEUE_KEY;
import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_QUEUE_LOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillOrderProducerTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ListOperations<String, String> listOperations;
    private TestProducer producer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        producer = new TestProducer();
        ReflectionTestUtils.setField(producer, "stringRedisTemplate", redisTemplate);
    }

    @Test
    void flushIntervalIsOneHundredMilliseconds() throws Exception {
        Field field = SeckillOrderProducer.class.getDeclaredField("FLUSH_INTERVAL_MS");
        field.setAccessible(true);
        assertEquals(100L, field.getLong(null));
    }

    @Test
    void oneScheduledPassSendsOnlyOneBatch() {
        int partition = 3;
        String queueKey = SECKILL_ORDER_QUEUE_KEY + partition;
        String lockKey = SECKILL_ORDER_QUEUE_LOCK_KEY + partition;
        List<String> firstBatch = Arrays.asList("message-1", "message-2");

        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(listOperations.range(queueKey, 0, 49)).thenReturn(firstBatch);
        producer.sendResult = true;

        producer.flushPendingOrders(partition);

        verify(listOperations, times(1)).range(queueKey, 0, 49);
        verify(listOperations).trim(queueKey, firstBatch.size(), -1);
        assertEquals(1, producer.sendCalls);
    }

    @Test
    void emptyQueueEndsPassWithoutSendingOrTrimming() {
        int partition = 4;
        String queueKey = SECKILL_ORDER_QUEUE_KEY + partition;
        String lockKey = SECKILL_ORDER_QUEUE_LOCK_KEY + partition;

        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(listOperations.range(queueKey, 0, 49)).thenReturn(Collections.emptyList());

        producer.flushPendingOrders(partition);

        assertEquals(0, producer.sendCalls);
        verify(listOperations, never()).trim(anyString(), anyLong(), anyLong());
    }

    @Test
    void failedBatchStaysInRedisForNextScheduledPass() {
        int partition = 5;
        String queueKey = SECKILL_ORDER_QUEUE_KEY + partition;
        String lockKey = SECKILL_ORDER_QUEUE_LOCK_KEY + partition;

        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(listOperations.range(queueKey, 0, 49)).thenReturn(Collections.singletonList("message"));
        producer.sendResult = false;

        producer.flushPendingOrders(partition);

        assertEquals(1, producer.sendCalls);
        verify(listOperations, never()).trim(anyString(), anyLong(), anyLong());
    }

    private static class TestProducer extends SeckillOrderProducer {
        private boolean sendResult;
        private int sendCalls;

        @Override
        boolean sendBatchWithRetry(List<String> payloads) {
            sendCalls++;
            return sendResult;
        }
    }
}
