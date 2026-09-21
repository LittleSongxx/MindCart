package com.smartore.voice.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 语音订单归因事件（会话 ↔ 订单的桥）。
 * <p>
 * 交易事实在 trade 的 MySQL（本表不复制订单状态机），这里只保留归因所需最小字段：
 * requestId 与交易侧幂等表的主键一致（前端代执行时透传），orderNo 由 order_result 回传后补齐。
 * 用途：统计语音贡献、按会话复盘"说了什么 → 下了哪单"、排查派发未成交的单。
 */
@Data
@Entity
@Table(name = "voice_order_event")
public class VoiceOrderEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** CREATE / CANCEL */
    @Column(length = 16, nullable = false)
    private String action;

    /** 派发给前端的幂等键；成交后与 trade 的 order_request_idempotency.request_id 一致 */
    @Column(name = "request_id", length = 64, nullable = false, unique = true)
    private String requestId;

    @Column(name = "order_no", length = 64)
    private String orderNo;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name")
    private String productName;

    private Integer quantity;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    /** DISPATCHED（已派发待前端执行）/ SUCCEEDED / FAILED */
    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "fail_reason")
    private String failReason;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
