package com.fuyouxuan.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static com.fuyouxuan.utils.RedisConstants.CACHE_NULL_TTL;
import static com.fuyouxuan.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        long baseMillis = unit.toMillis(time);
        long jitterMillis = Math.max(1L, baseMillis / 10L);
        long ttlMillis = baseMillis + ThreadLocalRandom.current().nextLong(jitterMillis);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttlMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 三级缓存查询：Caffeine → Redis → MySQL
     */
    public <R, ID> R queryWithMultiLevel(
            Cache<ID, R> caffeineCache,
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback,
            Long ttl, TimeUnit unit) {

        // 1. 查 Caffeine 本地缓存
        R r = caffeineCache.getIfPresent(id);
        if (r != null) {
            log.debug("Caffeine缓存命中, key={}{}", keyPrefix, id);
            return r;
        }

        // 2. 查 Redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            r = JSONUtil.toBean(json, type);
            caffeineCache.put(id, r);
            log.debug("Redis缓存命中, key={}", key);
            return r;
        }
        // 空值穿透保护
        if (json != null) {
            return null;
        }

        // 3. 冷缓存只允许一个请求回源，避免Caffeine和Redis同时失效时击穿MySQL
        String lockKey = LOCK_SHOP_KEY + "multi:" + id;
        RLock rebuildLock = redissonClient.getLock(lockKey);
        boolean locked;
        try {
            // No fixed lease time: Redisson watchdog renews the lock while cache rebuilding is alive.
            locked = rebuildLock.tryLock(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring cache rebuild lock", e);
        }
        if (!locked) {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for cache rebuild", e);
            }
            return queryWithMultiLevel(caffeineCache, keyPrefix, id, type, dbFallback, ttl, unit);
        }

        try {
            // Double check after obtaining the rebuild lock.
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                r = JSONUtil.toBean(json, type);
                caffeineCache.put(id, r);
                return r;
            }
            if (json != null) {
                return null;
            }

            r = dbFallback.apply(id);
            if (r == null) {
                long nullTtlSeconds = TimeUnit.MINUTES.toSeconds(CACHE_NULL_TTL)
                        + ThreadLocalRandom.current().nextLong(30);
                stringRedisTemplate.opsForValue().set(key, "", nullTtlSeconds, TimeUnit.SECONDS);
                return null;
            }

            this.set(key, r, ttl, unit);
            caffeineCache.put(id, r);
            log.debug("MySQL查询完成，已回填缓存, key={}", key);
            return r;
        } finally {
            if (rebuildLock.isHeldByCurrentThread()) {
                rebuildLock.unlock();
            }
        }
    }

}
