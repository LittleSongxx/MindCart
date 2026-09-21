package com.smartore.voice.service;

import com.smartore.voice.entity.SessionMessageEntity;
import com.smartore.voice.memory.ShortTermMemory;
import com.smartore.voice.repository.SessionMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对话流水（会话归档）与上下文回灌。
 * <p>
 * 写入一律异步且吞异常：审计是旁路，任何落库失败都不允许影响语音链路本身
 * （与事件发布同一条原则）。回灌用于 Redis 冷启动场景——进程重启/缓存过期后，
 * 从 PG 里把最近几轮摘要灌回 {@link ShortTermMemory}，会话上下文不丢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationJournal {

    private final SessionMessageRepository repo;
    private final ShortTermMemory shortTermMemory;

    /** 用户说了一句 */
    @Async
    public void userSaid(String sessionId, String text) {
        append(sessionId, "USER", text, null, null, null);
    }

    /** 助手回了一句（intent = 当轮生效分支；latencyMs 仅流式/同步回复有值） */
    @Async
    public void assistantSaid(String sessionId, String text, String agentName,
                              String intent, Integer latencyMs) {
        append(sessionId, "ASSISTANT", text, agentName, intent, latencyMs);
    }

    /** 系统事件（下单派发/成交/失败等），进流水便于按会话回放 */
    @Async
    public void systemEvent(String sessionId, String text) {
        append(sessionId, "SYSTEM", text, "order_event", null, null);
    }

    @Transactional
    public void append(String sessionId, String role, String text, String agentName,
                       String intent, Integer latencyMs) {
        try {
            if (text == null || text.isBlank()) return;
            int turn = repo.countBySessionId(sessionId) + 1;
            SessionMessageEntity m = new SessionMessageEntity();
            m.setSessionId(sessionId);
            m.setTurn(turn);
            m.setRole(role);
            m.setAgentName(agentName);
            m.setIntent(intent);
            m.setContentText(text);
            m.setTokens(0);
            m.setLatencyMs(latencyMs);
            repo.save(m);
        } catch (Exception e) {
            log.warn("[Journal] 落库失败（不影响对话）sessionId={} role={}: {}", sessionId, role, e.getMessage());
        }
    }

    public List<SessionMessageEntity> transcript(String sessionId) {
        return repo.findBySessionIdOrderByTurnAscIdAsc(sessionId);
    }

    public List<SessionMessageEntity> recent(String sessionId, int limit) {
        return repo.findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, limit));
    }

    /**
     * 上下文回灌：Redis 里没有短时记忆但 PG 有流水时（进程重启/缓存过期/换实例），
     * 把最近几轮重建进 ShortTermMemory，意图分类的"最近 3 轮摘要"不至于失忆。
     *
     * @return 是否发生了回灌
     */
    public boolean hydrateIfCold(String sessionId) {
        try {
            if (!shortTermMemory.recent(sessionId, 1).isEmpty()) return false;
            List<SessionMessageEntity> recent = recent(sessionId, 12);
            if (recent.isEmpty()) return false;
            // 倒序取回 → 正序回放
            for (int i = recent.size() - 1; i >= 0; i--) {
                SessionMessageEntity m = recent.get(i);
                if ("USER".equals(m.getRole())) continue;   // 摘要一行代表一轮（助手视角）
                shortTermMemory.append(sessionId, new ShortTermMemory.Turn(
                        "TURN", m.getIntent() == null ? "RESUMED" : m.getIntent(),
                        abbreviate(m.getContentText()), System.currentTimeMillis()));
            }
            log.info("[Journal] 会话上下文已从 PG 回灌 sessionId={} 行数={}", sessionId, recent.size());
            return true;
        } catch (Exception e) {
            log.warn("[Journal] 回灌失败（忽略）sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
