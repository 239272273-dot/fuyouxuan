package com.fuyouxuan.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Employee-facing welfare order view.
 */
@Data
public class VoucherOrderDTO {
    private Long orderId;
    private Long voucherId;
    private String voucherTitle;
    private Integer status;
    private LocalDateTime createTime;
}
