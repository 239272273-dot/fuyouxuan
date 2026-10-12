package com.fuyouxuan.service;

import com.fuyouxuan.dto.Result;
import com.fuyouxuan.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    void createVoucherOrders(List<VoucherOrder> voucherOrders);

    Result queryMyOrders();

    Result queryOrderStatus(Long orderId);

    Result payOrder(Long orderId);

    Result cancelOrder(Long orderId);

    void closeExpiredOrder(Long orderId, Long userId, Long voucherId);
}
