package com.smartore.voice.service;

import com.smartore.voice.entity.SessionEntity;
import com.smartore.voice.repository.SessionRepository;
import com.smartore.voice.repository.VoiceOrderEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository repo;
    private final VoiceOrderEventRepository orderEventRepo;

    @Transactional
    public SessionEntity openIfAbsent(String sessionId, Long userId, String channel) {
        return openIfAbsent(sessionId, userId, channel, null);
    }

    /** channel：HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK；boundProductId 仅商详页进入时有值 */
    @Transactional
    public SessionEntity openIfAbsent(String sessionId, Long userId, String channel, Long boundProductId) {
        return repo.findById(sessionId).orElseGet(() -> {
            SessionEntity s = new SessionEntity();
            s.setId(sessionId);
            s.setUserId(userId);
            s.setChannel(channel == null ? "HOME_ENTRY" : channel);
            s.setBoundProductId(boundProductId);
            s.setStartedAt(LocalDateTime.now());
            s.setLastActiveAt(LocalDateTime.now());
            s.setLocale("zh_cn");
            s.setTotalTokens(0);
            return repo.save(s);
        });
    }

    /** 每轮刷新活跃时间（运营统计"最后活跃"、超时会话清理的依据） */
    @Transactional
    public void touch(String sessionId) {
        repo.findById(sessionId).ifPresent(s -> {
            s.setLastActiveAt(LocalDateTime.now());
            repo.save(s);
        });
    }

    /**
     * 会话收口：写 ended_at 与 outcome 归因。
     * ORDERED = 本会话至少成交过一单；ABANDONED = 有对话但未成交（导购转化率的分子/分母）。
     */
    @Transactional
    public void close(String sessionId) {
        repo.findById(sessionId).ifPresent(s -> {
            boolean ordered = orderEventRepo.existsBySessionIdAndStatus(sessionId, "SUCCEEDED");
            s.setOutcome(ordered ? "ORDERED" : "ABANDONED");
            s.setEndedAt(LocalDateTime.now());
            s.setLastActiveAt(LocalDateTime.now());
            repo.save(s);
        });
    }

    public Long findUserId(String sessionId) {
        return repo.findById(sessionId).map(SessionEntity::getUserId).orElse(null);
    }

}
