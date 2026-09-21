package com.smartore.voice.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RedisMemory 工厂：TTL 跟随会话寿命（voice-shopping.session.ttl-minutes），
 * Agent 无活动即随会话一起过期，不遗留垃圾 key。
 */
@Component
@RequiredArgsConstructor
public class RedisMemoryFactory {

    private final StringRedisTemplate redis;

    @Value("${voice-shopping.session.ttl-minutes:30}")
    private int sessionTtlMinutes;

    public RedisMemory create(String sessionId, String agentName) {
        return new RedisMemory(redis, sessionId, agentName, Duration.ofMinutes(sessionTtlMinutes));
    }
}
