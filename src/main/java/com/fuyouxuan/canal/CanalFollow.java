package com.fuyouxuan.canal;

import lombok.Data;

/**
 * Canal binlog 映射类 — 字段名必须和 MySQL tb_follow 列名一致
 */
@Data
public class CanalFollow {
    private Long id;
    private Long user_id;
    private Long follow_user_id;
    private String create_time;
}
