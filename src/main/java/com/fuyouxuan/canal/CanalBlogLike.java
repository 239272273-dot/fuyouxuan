package com.fuyouxuan.canal;

import lombok.Data;

/**
 * Canal binlog 映射类 — 字段名必须和 MySQL tb_blog_like 列名一致
 */
@Data
public class CanalBlogLike {
    private Long id;
    private Long blog_id;
    private Long user_id;
    private Long create_time;
}
