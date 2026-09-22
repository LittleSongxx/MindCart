package com.mindcart.voice.integration;

import com.mindcart.common.result.Result;
import com.mindcart.goods.api.GoodsFeignClient;
import com.mindcart.goods.api.ProductSyncVO;
import com.mindcart.voice.entity.ProductEntity;
import com.mindcart.voice.repository.ProductRepository;
import com.mindcart.voice.service.ProductTextBuilder;
import com.mindcart.voice.service.ProductVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 商品目录同步：MindCart goods 服务 → 本地 voice_product（向量检索副本）。
 * <p>
 * 原则（与底座契约一致）：价格/库存/在售状态以 goods 实时数据为准，本地只存
 * 同步时刻快照用于检索与展示，对外回答前必须经 /internal/product/batch 复核。
 * embedding 按量计费：文本没变（embed_hash 一致）就不重算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogSyncService {

    private final GoodsFeignClient goodsClient;
    private final ProductRepository repo;
    private final ProductVectorService vectorService;
    private final ProductTextBuilder textBuilder;
    private final JdbcTemplate jdbc;

    public record SyncReport(int synced, int embedded, int offShelf, String message) {}

    /** 定时全量同步（默认 30 分钟）；启动后由 /voice/admin/reindex 或首 delay 触发 */
    @Scheduled(initialDelayString = "${voice-shopping.catalog.sync-initial-delay-ms:60000}",
            fixedDelayString = "${voice-shopping.catalog.sync-interval-ms:1800000}")
    public SyncReport syncAll() {
        int synced = 0, embedded = 0;
        List<Long> seenIds = new ArrayList<>();
        try {
            int page = 1;
            while (true) {
                Result<List<ProductSyncVO>> r = goodsClient.listOnSale(page, 100);
                List<ProductSyncVO> batch = (r != null && "200".equals(r.getCode()) && r.getData() != null)
                        ? r.getData() : List.of();
                if (batch.isEmpty()) break;
                for (ProductSyncVO vo : batch) {
                    ProductEntity e = upsert(vo);
                    seenIds.add(e.getId());
                    synced++;
                    String hash = sha256(textBuilder.build(e));
                    if (!hash.equals(e.getEmbedHash())) {
                        vectorService.upsertEmbedding(e);
                        jdbc.update("UPDATE voice_product SET embed_hash = ? WHERE id = ?", hash, e.getId());
                        embedded++;
                    }
                }
                if (batch.size() < 100) break;
                page++;
            }
            // 全量语义：本地残留但远端已不在售的，标记下架（不下硬删，保留历史会话的指代解析能力）
            int offShelf = seenIds.isEmpty() ? 0 : jdbc.update(
                    "UPDATE voice_product SET status='OFF_SHELF', updated_at=NOW() " +
                            "WHERE status='ON_SALE' AND id NOT IN (" +
                            seenIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")");
            log.info("[CatalogSync] synced={} embedded={} offShelf={}", synced, embedded, offShelf);
            return new SyncReport(synced, embedded, offShelf, "ok");
        } catch (Exception e) {
            log.error("[CatalogSync] 同步失败（保留本地现有目录继续服务）", e);
            return new SyncReport(synced, embedded, 0, "failed: " + e.getMessage());
        }
    }

    private ProductEntity upsert(ProductSyncVO vo) {
        ProductEntity e = repo.findById(vo.getId().longValue()).orElseGet(ProductEntity::new);
        e.setId(vo.getId().longValue());
        e.setName(vo.getName());
        // MindCart 类目是单层结构：l1/l2 同取类目名，保持检索/归一化两侧口径一致
        String category = vo.getCategoryName() == null ? "其他" : vo.getCategoryName();
        e.setCategoryL1(category);
        e.setCategoryL2(category);
        e.setBrand(vo.getBrandName());
        e.setPrice(vo.getPrice());
        e.setOriginalPrice(vo.getOriginalPrice());
        e.setSellingPoints(vo.getSellingPoint());
        e.setDescription(vo.getTags());
        Map<String, Object> attrs = new HashMap<>();
        if (vo.getTags() != null && !vo.getTags().isBlank()) {
            for (String tag : vo.getTags().split("[,，]")) {
                if (!tag.isBlank()) attrs.put("标签", tag.trim());
            }
        }
        e.setAttributes(attrs);
        e.setStatus("ON_SALE".equals(vo.getStatus()) ? "ON_SALE" : "OFF_SHELF");
        e.setIsNewArrival(false);
        e.setSyncedAt(java.time.LocalDateTime.now());
        return repo.save(e);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
