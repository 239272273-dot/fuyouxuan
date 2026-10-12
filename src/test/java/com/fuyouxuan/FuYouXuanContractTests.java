package com.fuyouxuan;

import com.fuyouxuan.entity.VoucherOrder;
import com.fuyouxuan.dto.VoucherOrderDTO;
import com.fuyouxuan.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class FuYouXuanContractTests {

    @Test
    void seckillLuaKeepsAdmissionAtomic() throws Exception {
        String lua = read("src/main/resources/seckill.lua");
        int stockCheck = lua.indexOf("get', stockKey");
        int duplicateCheck = lua.indexOf("hexists', orderKey");
        int idGeneration = lua.indexOf("incr', orderIdKey");
        int stockDecrease = lua.indexOf("incrby', stockKey, -1");
        int queueWrite = lua.indexOf("rpush', queueKey");

        assertTrue(stockCheck >= 0);
        assertTrue(stockCheck < duplicateCheck);
        assertTrue(duplicateCheck < idGeneration);
        assertTrue(idGeneration < stockDecrease);
        assertTrue(stockDecrease < queueWrite);
        assertTrue(lua.contains("seckill:order:status:"));
    }

    @Test
    void compensationIsIdempotentAndUpdatesOrderState() throws Exception {
        String lua = read("src/main/resources/compensate_seckill.lua");
        assertTrue(lua.contains("local currentOrderId = redis.call('hget'"));
        assertTrue(lua.contains("if currentOrderId == orderId then"));
        assertTrue(lua.contains("finalState"));
    }

    @Test
    void databaseOrderWriteIsTransactional() throws Exception {
        Transactional annotation = VoucherOrderServiceImpl.class
                .getMethod("createVoucherOrder", VoucherOrder.class)
                .getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertArrayEquals(new Class[]{Exception.class}, annotation.rollbackFor());
    }

    @Test
    void demoSchemaHasFinalIdempotencyConstraints() throws Exception {
        String sql = read("src/main/resources/db/fuyouxuan-demo.sql").toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("unique key uk_order_user_voucher (user_id, voucher_id)"));
        assertTrue(sql.contains("unique key uk_follow_relation (user_id, follow_user_id)"));
        assertTrue(sql.contains("unique key uk_blog_user (blog_id, user_id)"));
    }

    @Test
    void employeeOrderIdsAreSerializedAsText() throws Exception {
        assertEquals(String.class, VoucherOrderDTO.class.getDeclaredField("orderId").getType());
    }

    @Test
    void frontendUsesOnlyFuYouXuanBranding() throws Exception {
        String html = read("frontend/index.html");
        String app = read("frontend/js/app.js");
        assertTrue(html.contains("福优选"));
        assertTrue(app.contains("福优选"));
    }

    private String read(String relativePath) throws Exception {
        Path path = Paths.get(relativePath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
