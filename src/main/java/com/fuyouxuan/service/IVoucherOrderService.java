package com.fuyouxuan.service;

import com.fuyouxuan.dto.Result;
import com.fuyouxuan.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

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

    Result queryMyOrders();

    Result queryOrderStatus(Long orderId);
}
