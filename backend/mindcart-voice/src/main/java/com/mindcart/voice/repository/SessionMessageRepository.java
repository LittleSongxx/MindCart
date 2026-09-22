package com.mindcart.voice.repository;

import com.mindcart.voice.entity.SessionMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    List<SessionMessageEntity> findBySessionIdOrderByTurnAscIdAsc(String sessionId);

    /** 最近若干条（用于会话上下文回灌） */
    List<SessionMessageEntity> findBySessionIdOrderByIdDesc(String sessionId, Pageable pageable);

    int countBySessionId(String sessionId);
}
