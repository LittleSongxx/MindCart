package com.smartore.voice.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "voice_product")
public class ProductEntity {

    /** 与 Smartore goods 服务的 product.id 一致，由 CatalogSyncService 同步写入（非自增） */
    @Id
    private Long id;

    private String name;

    @Column(name = "category_l1", length = 32, nullable = false)
    private String categoryL1;

    @Column(name = "category_l2", length = 64, nullable = false)
    private String categoryL2;

    private String brand;

    /** 同步时刻价格快照；对外回答前必须经 /internal/product/batch 实时复核 */
    private BigDecimal price;

    @Column(name = "original_price")
    private BigDecimal originalPrice;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> attributes;

    private String description;

    @Column(name = "selling_points")
    private String sellingPoints;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "is_new_arrival")
    private Boolean isNewArrival;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    /** 向量化原文的 SHA-256：同步时文本没变就不重算 embedding */
    @Column(name = "embed_hash", length = 64)
    private String embedHash;

    // 注意：embedding 字段故意不映射进 Entity
    // 避免 JPA 每次 SELECT 都把 4KB 向量加载进内存
    // 向量读写统一走 ProductVectorService (JdbcTemplate)

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}