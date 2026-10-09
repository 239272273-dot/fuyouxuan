package com.fuyouxuan.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.fuyouxuan.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.fuyouxuan.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheInvalidationService {

    public static final String SHOP_INVALIDATION_CHANNEL = "cache:invalidate:shop";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<Long, Shop> shopCache;

    public void evictShop(Long shopId) {
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        stringRedisTemplate.convertAndSend(SHOP_INVALIDATION_CHANNEL, shopId.toString());
        evictLocalShop(shopId);
    }

    public void evictLocalShop(Long shopId) {
        shopCache.invalidate(shopId);
        log.info("Local welfare provider cache invalidated, id={}", shopId);
    }
}
