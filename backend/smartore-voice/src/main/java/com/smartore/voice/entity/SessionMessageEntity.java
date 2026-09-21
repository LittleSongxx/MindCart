package com.smartore.voice.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 对话流水（会话归档）：一次用户输入或一次助手输出一行。
 * 与 Redis 的短时记忆不同，本表是持久化的审计/回溯来源（谁在什么时候说了什么、走的哪条分支）。
 */
@Data
@Entity
@Table(name = "session_message")
public class SessionMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    /** 会话内轮次序号，从 1 递增（USER/ASSISTANT 共用同一序号，便于成对回放） */
    @Column(nullable = false)
    private Integer turn;

    /** USER / ASSISTANT / SYSTEM */
    @Column(length = 16, nullable = false)
    private String role;

    /** 产出该行的 Agent 名（ASSISTANT 才有，如 emotion_agent / order_receipt） */
    @Column(name = "agent_name", length = 32)
    private String agentName;

    /** 当轮生效意图（路由后的真实分支，排障与质量复盘的口径） */
    @Column(length = 32)
    private String intent;

    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    @Column(name = "content_audio_url")
    private String contentAudioUrl;

    @Column(nullable = false)
    private Integer tokens = 0;

    /** 编排耗时（毫秒，ASSISTANT 行才有） */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
