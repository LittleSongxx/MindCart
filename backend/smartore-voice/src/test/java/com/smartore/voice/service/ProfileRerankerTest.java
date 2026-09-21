package com.smartore.voice.service;

import com.smartore.voice.dto.RecommendedItem;
import com.smartore.voice.dto.UserProfileSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 画像规则重排：真实相似度 min-max 归一化后叠规则分。
 * 归一化语义：候选内 base 最高者→1.0、最低者→0.0；range=0（单候选/同分）时 norm=1.0。
 */
class ProfileRerankerTest {

    private final ProfileReranker reranker = new ProfileReranker();

    private RecommendedItem item(long id, double price, double baseScore, Map<String, Object> attrs) {
        return new RecommendedItem(id, "商品" + id, BigDecimal.valueOf(price), null,
                baseScore, attrs == null ? Map.of() : attrs);
    }

    private UserProfileSnapshot profile(Map<String, Double> brandAff, List<Long> purchased,
                                        double sensitivity, double avgOrder) {
        return new UserProfileSnapshot(1L, null, null, null, null, null, null,
                Map.of(), brandAff, List.of(), purchased,
                BigDecimal.valueOf(sensitivity), BigDecimal.valueOf(avgOrder));
    }

    @Test
    // 归一化后分值精确断言：base 1.0→norm 1.0（0.2档-0.10=0.90），base 0.90→norm 0.0（0.9档+0.25=0.25）
    void case01() {
        List<RecommendedItem> out = reranker.rerank(List.of(
                item(1, 200, 1.00, null),
                item(2, 900, 0.90, null)),
                null, Map.of("budget", 1000));
        assertEquals(1L, out.get(0).productId());
        assertEquals(0.90, out.get(0).matchScore(), 1e-9);
        assertEquals(0.25, out.get(1).matchScore(), 1e-9);
    }

    @Test
    // 主推档反超：锚点0.25大于归一化相似度差时，主推档压过相似度更高的远低档
    void case02() {
        List<RecommendedItem> out = reranker.rerank(List.of(
                item(1, 950, 0.80, null),     // norm 0.0 + 0.25 = 0.25
                item(2, 100, 1.00, null)),    // norm 1.0 - 0.10 = 0.90 → 商品2仍高
                null, Map.of("budget", 1000));
        // 0.25 < 0.90：锚点单独不够反超 0.8 的相似度差——锚点影响的是"接近的候选之间"
        assertEquals(2L, out.get(0).productId());
    }

    @Test
    // 中等档微加_远低于预算微减（base 相同 → range 0 → norm 均 1.0，纯规则分生效）
    void case03() {
        List<RecommendedItem> out = reranker.rerank(List.of(
                item(1, 500, 1.0, null),
                item(2, 100, 1.0, null)),
                null, Map.of("budget", 1000));
        assertEquals(1.05, out.get(0).matchScore(), 1e-9);
        assertEquals(0.90, out.get(1).matchScore(), 1e-9);
    }

    @Test
    // 主推档下界容差0.58_299元对500预算（0.598）不再误杀
    void case04() {
        List<RecommendedItem> out = reranker.rerank(List.of(item(1, 299, 1.0, null)),
                null, Map.of("budget", 500));
        assertEquals(1.25, out.get(0).matchScore(), 1e-9);
    }

    @Test
    // 超预算不加分
    void case05() {
        List<RecommendedItem> out = reranker.rerank(List.of(item(1, 2000, 1.0, null)),
                null, Map.of("budget", 1000));
        assertEquals(1.0, out.get(0).matchScore(), 1e-9);
    }

    @Test
    // 品牌亲和加权
    void case06() {
        List<RecommendedItem> out = reranker.rerank(List.of(
                        item(1, 100, 1.0, Map.of("brand", "Nike")),
                        item(2, 100, 1.0, Map.of("brand", "Adidas"))),
                profile(Map.of("Nike", 0.75), null, 0.0, 10000), Map.of());
        assertEquals(1L, out.get(0).productId());
        assertEquals(1.15, out.get(0).matchScore(), 1e-9);
        assertEquals(1.0, out.get(1).matchScore(), 1e-9);
    }

    @Test
    // 复购去重：相似度接近时（差0.1<扣分0.3）买过的被压后
    void case07() {
        List<RecommendedItem> out = reranker.rerank(List.of(
                        item(1, 100, 1.00, null),     // 买过：norm 1.0 - 0.3 = 0.70
                        item(2, 100, 0.90, null)),    // norm 0.0 → 0.0
                profile(Map.of(), List.of(1L), 0.0, 10000), Map.of());
        assertEquals(1L, out.get(0).productId());
        assertEquals(0.70, out.get(0).matchScore(), 1e-9);
    }

    @Test
    // 价格敏感_超平均客单价太多扣分
    void case08() {
        List<RecommendedItem> out = reranker.rerank(List.of(item(1, 1000, 1.0, null)),
                profile(Map.of(), null, 0.8, 500), Map.of());
        assertEquals(0.88, out.get(0).matchScore(), 1e-9);
    }

    @Test
    // 空候选与单候选（range=0 → norm=1.0）安全
    void case09() {
        assertTrue(reranker.rerank(List.of(), null, Map.of()).isEmpty());
        List<RecommendedItem> out = reranker.rerank(List.of(item(1, 600, 0.82, null)),
                null, Map.of("budget", 1000));
        assertEquals(1.25, out.get(0).matchScore(), 1e-9);
    }
}
