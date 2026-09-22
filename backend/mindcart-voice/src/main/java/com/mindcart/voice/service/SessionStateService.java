package com.mindcart.voice.service;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcart.voice.entity.SessionStateEntity;
import com.mindcart.voice.repository.SessionStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionStateService {

    private final SessionStateRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    private String key(String sessionId) {
        return "vs:session:" + sessionId;
    }

    @SneakyThrows
    public SessionStateEntity load(String sessionId) {
        String cached = redis.opsForValue().get(key(sessionId));
        if (cached != null) return mapper.readValue(cached, SessionStateEntity.class);
        Optional<SessionStateEntity> fromDb = repo.findById(sessionId);
        if (fromDb.isPresent()) {
            syncToRedis(fromDb.get());
            return fromDb.get();
        }
        SessionStateEntity init = new SessionStateEntity();
        init.setSessionId(sessionId);
        init.setPhase("INTENT");
        // 显式空槽位：DB 列 NOT NULL，且 Hibernate 显式 null 会覆盖 DB DEFAULT
        init.setSlots(new java.util.HashMap<>());
        return init;
    }

    @Transactional
    @SneakyThrows
    public void save(SessionStateEntity state) {
        repo.save(state);
        try {
            syncToRedis(state);       // 缓存：Redis 后写，挂了吞掉
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis 同步失败，下次 load 会从 PG 重建 sessionId={}", state.getSessionId(), e);
        }
    }

    @SneakyThrows
    private void syncToRedis(SessionStateEntity state) {
        redis.opsForValue().set(key(state.getSessionId()),
                mapper.writeValueAsString(state),
                Duration.ofMinutes(30));
    }
}