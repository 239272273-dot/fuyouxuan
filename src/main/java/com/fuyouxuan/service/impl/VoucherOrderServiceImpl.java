package com.fuyouxuan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fuyouxuan.dto.Result;
import com.fuyouxuan.dto.VoucherOrderDTO;
import com.fuyouxuan.entity.VoucherOrder;
import com.fuyouxuan.entity.Voucher;
import com.fuyouxuan.mapper.VoucherOrderMapper;
import com.fuyouxuan.mq.SeckillOrderProducer;
import com.fuyouxuan.service.ISeckillVoucherService;
import com.fuyouxuan.service.IVoucherOrderService;
import com.fuyouxuan.service.IVoucherService;
import com.fuyouxuan.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.fuyouxuan.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 发送 RocketMQ 事务消息（本地事务中执行 Lua 扣库存）
        Long orderId = seckillOrderProducer.sendOrderMessage(voucherId, userId);
        if (orderId == null) {
            return Result.fail("系统繁忙，请重试");
        }
        // 返回订单id
        // JavaScript numbers cannot represent a 16-digit distributed ID exactly.
        // Return it as text so browsers never round the final digits.
        return Result.ok(orderId.toString());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 数据库主键和 (user_id, voucher_id) 唯一索引负责最终幂等。
        // 先插入订单，重复消息会尽早触发 DuplicateKeyException。
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单创建失败");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            // 抛出异常后，事务会同时回滚订单插入。
            log.error("MySQL库存不足, voucherId={}, userId={}", voucherId, userId);
            throw new RuntimeException("MySQL库存不足");
        }

        log.info("订单创建成功, orderId={}", voucherOrder.getId());
    }

    @Override
    public Result queryMyOrders() {
        Long userId = UserHolder.getUser().getId();
        List<VoucherOrder> orders = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .list();
        if (orders.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<VoucherOrderDTO> result = new ArrayList<>(orders.size());
        for (VoucherOrder order : orders) {
            Voucher voucher = voucherService.getById(order.getVoucherId());
            VoucherOrderDTO dto = new VoucherOrderDTO();
            dto.setOrderId(order.getId());
            dto.setVoucherId(order.getVoucherId());
            dto.setVoucherTitle(voucher == null ? "企业福利" : voucher.getTitle());
            dto.setStatus(order.getStatus());
            dto.setCreateTime(order.getCreateTime());
            result.add(dto);
        }
        return Result.ok(result);
    }

    @Override
    public Result queryOrderStatus(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query().eq("id", orderId).eq("user_id", userId).one();
        String state = order == null
                ? stringRedisTemplate.opsForValue().get(SECKILL_ORDER_STATUS_KEY + orderId)
                : "SUCCESS";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("status", state == null ? "UNKNOWN" : state);
        return Result.ok(payload);
    }
}
