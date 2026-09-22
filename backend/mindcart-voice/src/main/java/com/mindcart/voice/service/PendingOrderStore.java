package com.mindcart.voice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class PendingOrderStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public record PendingOrder(
            String sessionId,
            Long userId,
            Long productId,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal totalAmount,
            /** 幂等键：前端代执行下单时透传给 trade 的 requestId，重复确认/网络重试不会重复建单 */
            String requestId
    ) {}

    private String key(String sessionId) {
        return "vs:pending_order:" + sessionId;
    }

    @SneakyThrows
    public void put(PendingOrder o) {
        redis.opsForValue().set(key(o.sessionId()),
                mapper.writeValueAsString(o), Duration.ofMinutes(10));
    }

    @SneakyThrows
    public PendingOrder get(String sessionId) {
        String raw = redis.opsForValue().get(key(sessionId));
        return raw == null ? null : mapper.readValue(raw, PendingOrder.class);
    }

    /**
     * 原子取出并删除（GETDEL，Redis 6.2+）：并发的两个 confirm 只有一个能
     * 拿到 pending 单，另一个得到 null——订单防重复提交的第一道闸门。
     */
    @SneakyThrows
    public PendingOrder atomicConsume(String sessionId) {
        String raw = redis.opsForValue().getAndDelete(key(sessionId));
        return raw == null ? null : mapper.readValue(raw, PendingOrder.class);
    }

    public void remove(String sessionId) {
        redis.delete(key(sessionId));
    }
}