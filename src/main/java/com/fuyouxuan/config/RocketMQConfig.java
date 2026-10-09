package com.fuyouxuan.config;

import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 配置扩展点。
 *
 * 当前生产者和消费者使用 RocketMQ 原生客户端显式创建，便于控制批量发送、ACK、
 * 重试与死信补偿。保留这个配置类，后续可以集中挂载监控、TLS 或客户端定制 Bean。
 */
@Configuration
public class RocketMQConfig {
    // 预留集中配置入口；当前不需要额外 Bean。
}
