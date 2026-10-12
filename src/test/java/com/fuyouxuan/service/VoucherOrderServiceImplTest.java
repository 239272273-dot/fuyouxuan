package com.fuyouxuan.service;

import com.fuyouxuan.dto.Result;
import com.fuyouxuan.dto.UserDTO;
import com.fuyouxuan.mq.SeckillOrderProducer;
import com.fuyouxuan.service.impl.VoucherOrderServiceImpl;
import com.fuyouxuan.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoucherOrderServiceImplTest {

    private VoucherOrderServiceImpl service;
    private SeckillOrderProducer producer;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        producer = mock(SeckillOrderProducer.class);
        ReflectionTestUtils.setField(service, "seckillOrderProducer", producer);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void reportsSoldOutInsteadOfGenericBusyMessage() {
        when(producer.sendOrderMessage(3L, 7L)).thenReturn("1");

        Result result = service.seckillVoucher(3L);

        assertFalse(result.getSuccess());
        assertEquals("该福利已抢完，看看其他福利吧", result.getErrorMsg());
    }

    @Test
    void reportsDuplicateOrderInsteadOfGenericBusyMessage() {
        when(producer.sendOrderMessage(3L, 7L)).thenReturn("2");

        Result result = service.seckillVoucher(3L);

        assertFalse(result.getSuccess());
        assertEquals("你已领取过该福利，无需重复下单", result.getErrorMsg());
    }

    @Test
    void returnsDistributedOrderIdAsTextWhenAccepted() {
        when(producer.sendOrderMessage(3L, 7L)).thenReturn("0:1234567890123456");

        Result result = service.seckillVoucher(3L);

        assertTrue(result.getSuccess());
        assertEquals("1234567890123456", result.getData());
    }
}
