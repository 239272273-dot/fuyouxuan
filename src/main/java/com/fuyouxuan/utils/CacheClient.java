package com.fuyouxuan.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.Collections;
import java.util.UUID;

import static com.fuyouxuan.utils.RedisConstants.CACHE_NULL_TTL;
import static com.fuyouxuan.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

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

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询福利提供方缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询福利提供方缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回福利提供方信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue = UUID.randomUUID().toString();
        boolean isLock = tryLock(lockKey, lockValue);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey, lockValue);
                }
            });
        }
        // 6.4.返回过期的福利提供方信息
        return r;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询福利提供方缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue = UUID.randomUUID().toString();
        R r = null;
        try {
            boolean isLock = tryLock(lockKey, lockValue);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey, lockValue);
        }
        // 8.返回
        return r;
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

    private boolean tryLock(String key, String value) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, value, 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key, String value) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
    }
}
