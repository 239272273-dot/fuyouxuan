package com.fuyouxuan.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_QUEUE_KEY = "seckill:order:queue:";
    public static final String SECKILL_ORDER_QUEUE_LOCK_KEY = "lock:seckill:order:queue:flush:";
    public static final String SECKILL_ORDER_STATUS_KEY = "seckill:order:status:";
    public static final Long SECKILL_ORDER_STATUS_TTL = 1L;
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
}
