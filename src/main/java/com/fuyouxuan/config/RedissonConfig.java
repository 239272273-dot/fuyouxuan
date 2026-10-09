package com.fuyouxuan.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.redis.sentinel.master}") String masterName,
            @Value("${spring.redis.sentinel.nodes}") String sentinelNodes,
            @Value("${redisson.lock-watchdog-timeout:30000}") long watchdogTimeout) {
        Config config = new Config();
        config.setLockWatchdogTimeout(watchdogTimeout);
        SentinelServersConfig sentinel = config.useSentinelServers()
                .setMasterName(masterName);
        Arrays.stream(sentinelNodes.split(","))
                .map(String::trim)
                .filter(node -> !node.isEmpty())
                .map(node -> node.startsWith("redis://") ? node : "redis://" + node)
                .forEach(sentinel::addSentinelAddress);
        return Redisson.create(config);
    }
}
