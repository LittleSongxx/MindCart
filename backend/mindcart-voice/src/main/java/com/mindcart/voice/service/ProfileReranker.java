package com.mindcart.voice.service;

import com.mindcart.voice.dto.RecommendedItem;
import com.mindcart.voice.dto.UserProfileSnapshot;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 画像规则重排。打分模型：
 *   final = norm(真实余弦相似度) + 规则分（预算锚点/品牌亲和/价格敏感/复购去重）
 * 相似度先在候选集内做 min-max 归一化到 [0,1]——cosine 相似度天然挤在 0.7~0.95 的窄带里，
 * 不归一化的话 +0.25 的锚点分和它不在一个量纲上（消融 A1 发现的问题）。
 */
@Component
public class ProfileReranker {

    /** 主推档下界：预算的 58%（原 0.60，299/500=0.598 这类边界值差 0.002 被误杀）。 */
    public static final double PRIMARY_BAND_LOW = 0.58;

    public List<RecommendedItem> rerank(List<RecommendedItem> candidates,
                                        UserProfileSnapshot profile,
                                        Map<String, Object> slots) {
        if (candidates.isEmpty()) return candidates;
        // profile 为空时也要走预算锚点打分，不能直接 return
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (RecommendedItem it : candidates) {
            double s = it.matchScore();
            min = Math.min(min, s);
            max = Math.max(max, s);
        }
        double range = max - min;
        final double fMin = min, fRange = range;
        return candidates.stream()
                .map(it -> it.withMatchScore(computeScore(it, profile, slots,
                        range == 0 ? 1.0 : (it.matchScore() - fMin) / fRange)))
                .sorted(Comparator.comparingDouble(RecommendedItem::matchScore).reversed())
                .collect(Collectors.toList());
    }

    private double computeScore(RecommendedItem item, UserProfileSnapshot p,
                                Map<String, Object> slots, double normSim) {
        double score = normSim;

        // ===== budget 锚点加分：用户给了预算上限时，价格越接近上限（主推档）的越优先 =====
        // 用户说"预算 2000"时客单心锚在 2000 附近，不是奔着 500 去的；
        // 推荐应倾向接近 budget 的中高端，而不是一来就把最低价顶上来。
        Object budgetObj = slots == null ? null : slots.get("budget");
        if (budgetObj instanceof Number bn && item.price() != null) {
            double budget = bn.doubleValue();
            double price = item.price().doubleValue();
            if (price <= budget && budget > 0) {
                double ratio = price / budget;
                if (ratio >= PRIMARY_BAND_LOW)    score += 0.25;    // 主推档，最加分
                else if (ratio >= 0.4)            score += 0.05;    // 中等档，略加分
                else                              score -= 0.10;    // 远低于预算，轻微扣分（可能档次不够）
            }
        }

        if (p != null) {
            // 偏好品牌加分
            String brand = Objects.toString(item.attributes().get("brand"), "");
            Double brandAff = p.brandAffinity() == null ? 0 : p.brandAffinity().getOrDefault(brand, 0.0);
            score += brandAff * 0.2;

            // 价格敏感：超过用户平均客单价太多扣分
            if (p.avgOrderAmount() != null && item.price() != null) {
                double ratio = item.price().doubleValue() / p.avgOrderAmount().doubleValue();
                if (ratio > 1.5) {
                    double sensitivity = p.priceSensitivity() == null ? 0.5 : p.priceSensitivity().doubleValue();
                    score -= 0.15 * sensitivity;
                }
            }

            // 最近已经买过同款/同类扣分
            if (p.recentPurchased() != null && p.recentPurchased().contains(item.productId())) {
                score -= 0.3;
            }
        }

        return score;
    }
}
