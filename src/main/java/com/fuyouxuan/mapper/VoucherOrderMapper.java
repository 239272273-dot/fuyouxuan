package com.fuyouxuan.mapper;

import com.fuyouxuan.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    @Insert("INSERT IGNORE INTO tb_voucher_order (id, user_id, voucher_id) " +
            "VALUES (#{id}, #{userId}, #{voucherId})")
    int insertIgnore(VoucherOrder order);

    @Update("UPDATE tb_voucher_order SET status = 2, pay_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{orderId} AND user_id = #{userId} AND status = 1")
    int markPaid(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Select("SELECT status FROM tb_voucher_order WHERE id = #{orderId} FOR UPDATE")
    Integer selectStatusForUpdate(@Param("orderId") Long orderId);

    @Delete("DELETE FROM tb_voucher_order WHERE id = #{orderId} AND user_id = #{userId} AND status = 1")
    int deleteUnpaidByUser(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Delete("DELETE FROM tb_voucher_order WHERE id = #{orderId} AND status = 1")
    int deleteUnpaid(@Param("orderId") Long orderId);
}
