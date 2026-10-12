package com.fuyouxuan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fuyouxuan.dto.Result;
import com.fuyouxuan.dto.VoucherOrderDTO;
import com.fuyouxuan.entity.VoucherOrder;
import com.fuyouxuan.entity.Voucher;
import com.fuyouxuan.entity.UserInfo;
import com.fuyouxuan.mapper.VoucherOrderMapper;
import com.fuyouxuan.mq.SeckillOrderProducer;
import com.fuyouxuan.service.ISeckillVoucherService;
import com.fuyouxuan.service.IUserInfoService;
import com.fuyouxuan.service.IVoucherOrderService;
import com.fuyouxuan.service.IVoucherService;
import com.fuyouxuan.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private static final int STATUS_UNPAID = 1;
    private static final int STATUS_PAID = 2;
    private static final int PAYMENT_MINUTES = 5;

    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;

    static {
        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("compensate_seckill.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 发送 RocketMQ 事务消息（本地事务中执行 Lua 扣库存）
        String admissionResult;
        try {
            admissionResult = seckillOrderProducer.sendOrderMessage(voucherId, userId);
        } catch (Exception e) {
            log.error("秒杀资格校验异常, voucherId={}, userId={}", voucherId, userId, e);
            return Result.fail("抢购暂未成功，请稍后重试");
        }
        if ("1".equals(admissionResult)) {
            return Result.fail("该福利已抢完，看看其他福利吧");
        }
        if ("2".equals(admissionResult)) {
            return Result.fail("你已领取过该福利，无需重复下单");
        }
        if (admissionResult == null || !admissionResult.startsWith("0:")) {
            return Result.fail("抢购暂未成功，请稍后重试");
        }
        String orderId = admissionResult.substring(2);
        // 返回订单id
        // JavaScript numbers cannot represent a 16-digit distributed ID exactly.
        // Return it as text so browsers never round the final digits.
        return Result.ok(orderId);
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
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrders(List<VoucherOrder> voucherOrders) {
        if (voucherOrders == null || voucherOrders.isEmpty()) {
            return;
        }

        Map<Long, Integer> insertedByVoucher = new LinkedHashMap<>();
        for (VoucherOrder order : voucherOrders) {
            int inserted = baseMapper.insertIgnore(order);
            if (inserted > 0) {
                insertedByVoucher.merge(order.getVoucherId(), 1, Integer::sum);
            }
        }

        for (Map.Entry<Long, Integer> entry : insertedByVoucher.entrySet()) {
            Long voucherId = entry.getKey();
            Integer count = entry.getValue();
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - " + count)
                    .eq("voucher_id", voucherId)
                    .ge("stock", count)
                    .update();
            if (!success) {
                throw new IllegalStateException("MySQL库存不足, voucherId=" + voucherId);
            }
        }

        log.debug("批量订单创建成功, received={}, inserted={}",
                voucherOrders.size(), insertedByVoucher.values().stream().mapToInt(Integer::intValue).sum());
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
            dto.setOrderId(String.valueOf(order.getId()));
            dto.setVoucherId(order.getVoucherId());
            dto.setVoucherTitle(voucher == null ? "企业福利" : voucher.getTitle());
            dto.setStatus(order.getStatus());
            dto.setPayCredits(requiredCredits(voucher));
            dto.setCreateTime(order.getCreateTime());
            dto.setExpiresAt(order.getCreateTime() == null
                    ? null : order.getCreateTime().plusMinutes(PAYMENT_MINUTES));
            result.add(dto);
        }
        return Result.ok(result);
    }

    @Override
    public Result queryOrderStatus(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query().eq("id", orderId).eq("user_id", userId).one();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", String.valueOf(orderId));
        if (order == null) {
            String state = stringRedisTemplate.opsForValue().get(SECKILL_ORDER_STATUS_KEY + orderId);
            payload.put("status", state == null ? "UNKNOWN" : state);
            return Result.ok(payload);
        }

        Voucher voucher = voucherService.getById(order.getVoucherId());
        payload.put("status", order.getStatus() == STATUS_PAID ? "PAID" : "UNPAID");
        payload.put("voucherId", order.getVoucherId());
        payload.put("voucherTitle", voucher == null ? "企业福利" : voucher.getTitle());
        payload.put("payCredits", requiredCredits(voucher));
        payload.put("createTime", order.getCreateTime());
        payload.put("expiresAt", order.getCreateTime() == null
                ? null : order.getCreateTime().plusMinutes(PAYMENT_MINUTES));
        return Result.ok(payload);
    }

    @Override
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        PaymentOutcome outcome = transactionTemplate.execute(status -> {
            VoucherOrder current = query().eq("id", orderId).eq("user_id", userId).one();
            if (current == null) {
                return PaymentOutcome.failed("订单已取消或已过期");
            }
            if (current.getStatus() == STATUS_PAID) {
                return PaymentOutcome.paid(currentBalance(userId), true);
            }
            if (current.getStatus() != STATUS_UNPAID) {
                return PaymentOutcome.failed("当前订单不能付款");
            }

            Voucher voucher = voucherService.getById(current.getVoucherId());
            if (voucher == null) {
                return PaymentOutcome.failed("福利不存在");
            }
            int payCredits = requiredCredits(voucher);

            int changed = baseMapper.markPaid(orderId, userId);
            if (changed == 0) {
                Integer latestStatus = baseMapper.selectStatusForUpdate(orderId);
                if (latestStatus != null && latestStatus == STATUS_PAID) {
                    return PaymentOutcome.paid(currentBalance(userId), true);
                }
                return PaymentOutcome.failed("订单已取消或已过期");
            }

            if (payCredits > 0 && !userInfoService.deductCredits(userId, payCredits)) {
                status.setRollbackOnly();
                return PaymentOutcome.failed("豆子余额不足");
            }
            return PaymentOutcome.paid(currentBalance(userId), false);
        });

        if (outcome == null || !outcome.success) {
            return Result.fail(outcome == null ? "付款失败，请稍后重试" : outcome.message);
        }
        stringRedisTemplate.opsForValue().set(
                SECKILL_ORDER_STATUS_KEY + orderId, "PAID", 1, TimeUnit.DAYS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", String.valueOf(orderId));
        payload.put("status", "PAID");
        payload.put("credits", outcome.credits);
        payload.put("alreadyPaid", outcome.alreadyPaid);
        return Result.ok(payload);
    }

    @Override
    public Result cancelOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        CloseOutcome outcome = closeInDatabase(orderId, userId, null);
        if (outcome.state == CloseState.PAID) {
            return Result.fail("订单已付款，不能取消");
        }
        if (outcome.state == CloseState.ABSENT) {
            return Result.fail("订单已取消或不存在");
        }
        compensateRedis(outcome.voucherId, outcome.userId, orderId, "CANCELLED");
        return Result.ok("CANCELLED");
    }

    @Override
    public void closeExpiredOrder(Long orderId, Long userId, Long voucherId) {
        CloseOutcome outcome = closeInDatabase(orderId, userId, voucherId);
        if (outcome.state == CloseState.PAID) {
            return;
        }
        // ABSENT means the asynchronous MySQL order was never created or a previous close
        // already removed it. The order-id-aware Lua script makes this retry safe.
        compensateRedis(voucherId, userId, orderId, "CANCELLED");
    }

    private CloseOutcome closeInDatabase(Long orderId, Long expectedUserId, Long expectedVoucherId) {
        CloseOutcome outcome = transactionTemplate.execute(status -> {
            VoucherOrder current = getById(orderId);
            if (current == null) {
                return CloseOutcome.absent(expectedUserId, expectedVoucherId);
            }
            if (expectedUserId != null && !expectedUserId.equals(current.getUserId())) {
                throw new IllegalStateException("订单用户不匹配, orderId=" + orderId);
            }
            if (expectedVoucherId != null && !expectedVoucherId.equals(current.getVoucherId())) {
                throw new IllegalStateException("订单福利不匹配, orderId=" + orderId);
            }
            if (current.getStatus() == STATUS_PAID) {
                return CloseOutcome.paid(current.getUserId(), current.getVoucherId());
            }

            int deleted = expectedUserId == null
                    ? baseMapper.deleteUnpaid(orderId)
                    : baseMapper.deleteUnpaidByUser(orderId, expectedUserId);
            if (deleted == 0) {
                Integer latestStatus = baseMapper.selectStatusForUpdate(orderId);
                return latestStatus != null && latestStatus == STATUS_PAID
                        ? CloseOutcome.paid(current.getUserId(), current.getVoucherId())
                        : CloseOutcome.absent(current.getUserId(), current.getVoucherId());
            }

            boolean stockRestored = seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", current.getVoucherId())
                    .update();
            if (!stockRestored) {
                throw new IllegalStateException("恢复MySQL库存失败, orderId=" + orderId);
            }
            return CloseOutcome.closed(current.getUserId(), current.getVoucherId());
        });
        if (outcome == null) {
            throw new IllegalStateException("关闭订单失败, orderId=" + orderId);
        }
        return outcome;
    }

    private void compensateRedis(Long voucherId, Long userId, Long orderId, String state) {
        if (voucherId == null || userId == null) {
            throw new IllegalStateException("缺少Redis补偿参数, orderId=" + orderId);
        }
        stringRedisTemplate.execute(
                COMPENSATE_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString(),
                state
        );
    }

    private int currentBalance(Long userId) {
        UserInfo info = userInfoService.getOrCreateDefault(userId);
        return info == null || info.getCredits() == null ? 0 : info.getCredits();
    }

    private static int requiredCredits(Voucher voucher) {
        if (voucher == null || voucher.getPayValue() == null || voucher.getPayValue() <= 0) {
            return 0;
        }
        return (int) ((voucher.getPayValue() + 99L) / 100L);
    }

    private enum CloseState { CLOSED, PAID, ABSENT }

    private static class CloseOutcome {
        private final CloseState state;
        private final Long userId;
        private final Long voucherId;

        private CloseOutcome(CloseState state, Long userId, Long voucherId) {
            this.state = state;
            this.userId = userId;
            this.voucherId = voucherId;
        }

        private static CloseOutcome closed(Long userId, Long voucherId) {
            return new CloseOutcome(CloseState.CLOSED, userId, voucherId);
        }

        private static CloseOutcome paid(Long userId, Long voucherId) {
            return new CloseOutcome(CloseState.PAID, userId, voucherId);
        }

        private static CloseOutcome absent(Long userId, Long voucherId) {
            return new CloseOutcome(CloseState.ABSENT, userId, voucherId);
        }
    }

    private static class PaymentOutcome {
        private final boolean success;
        private final String message;
        private final int credits;
        private final boolean alreadyPaid;

        private PaymentOutcome(boolean success, String message, int credits, boolean alreadyPaid) {
            this.success = success;
            this.message = message;
            this.credits = credits;
            this.alreadyPaid = alreadyPaid;
        }

        private static PaymentOutcome paid(int credits, boolean alreadyPaid) {
            return new PaymentOutcome(true, null, credits, alreadyPaid);
        }

        private static PaymentOutcome failed(String message) {
            return new PaymentOutcome(false, message, 0, false);
        }
    }
}
