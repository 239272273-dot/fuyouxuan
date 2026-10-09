package com.fuyouxuan.config;

import com.fuyouxuan.utils.CacheInvalidationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
public class CacheInvalidationConfig {

    @Bean
    public RedisMessageListenerContainer cacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationService invalidationService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String value = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                invalidationService.evictLocalShop(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Redis TTL remains the safety net for malformed events.
            }
        }, new ChannelTopic(CacheInvalidationService.SHOP_INVALIDATION_CHANNEL));
        return container;
    }
}
