package com.smartore.voice.repository;

import com.smartore.voice.entity.VoiceOrderEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VoiceOrderEventRepository extends JpaRepository<VoiceOrderEventEntity, Long> {

    Optional<VoiceOrderEventEntity> findByRequestId(String requestId);

    /** 按订单号找原成交事件（取消派发时取 userId 归因） */
    Optional<VoiceOrderEventEntity> findFirstByOrderNoAndStatus(String orderNo, String status);

    List<VoiceOrderEventEntity> findBySessionIdOrderByIdAsc(String sessionId);

    /** 该会话是否已成交过（会话 outcome=ORDERED 的判据） */
    boolean existsBySessionIdAndStatus(String sessionId, String status);

    List<VoiceOrderEventEntity> findAllByOrderByIdDesc(Pageable pageable);

    long countByStatus(String status);
}
