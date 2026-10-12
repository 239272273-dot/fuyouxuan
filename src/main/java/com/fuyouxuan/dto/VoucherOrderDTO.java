package com.fuyouxuan.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Employee-facing welfare order view.
 */
@Data
public class VoucherOrderDTO {
    /**
     * Distributed IDs exceed JavaScript's safe integer range, so expose them as text.
     */
    private String orderId;
    private Long voucherId;
    private String voucherTitle;
    private Integer status;
    private Integer payCredits;
    private LocalDateTime createTime;
    private LocalDateTime expiresAt;
}
