package com.smartore.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 限流键：已登录用户按 userId（从认证过滤器写入的属性取，回退到 IP），
 * Redis 令牌桶（replenish/burst 在 yml 中配置）。
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            Object userId = exchange.getAttributes().get("userId");
            if (userId != null) {
                return Mono.just("user:" + userId);
            }
            String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (ip == null || ip.isBlank()) {
                ip = exchange.getRequest().getRemoteAddress() == null
                        ? "unknown"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            }
            return Mono.just("ip:" + ip);
        };
    }

    @Bean
    public RedisRateLimiter redisRateLimiter(
            @org.springframework.beans.factory.annotation.Value("${SMARTORE_RATE_REPLENISH:20}") int replenish,
            @org.springframework.beans.factory.annotation.Value("${SMARTORE_RATE_BURST:60}") int burst) {
        return new RedisRateLimiter(replenish, burst);
    }
}
