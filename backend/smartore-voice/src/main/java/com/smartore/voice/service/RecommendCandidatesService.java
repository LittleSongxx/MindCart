package com.smartore.voice.service;

import com.smartore.voice.dto.RecommendedItem;
import com.smartore.voice.dto.SessionScope;
import com.smartore.voice.entity.ProductEntity;
import com.smartore.voice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RecommendCandidatesService {

    private final ProductVectorService vector;
    private final ProductRepository repo;
    private final ScopeFilterBuilder scopeFilterBuilder; // 新增

    /**
     * 为语义精排构建与向量库同源的富文本文档（消融 A5 实测：贫文档 -4pp、富文档 +2pp，
     * 文档表征是精排质量的决定变量）。
     */
    public java.util.Map<Long, String> buildRichDocs(List<Long> ids) {
        var textBuilder = new ProductTextBuilder();
        java.util.Map<Long, String> docs = new java.util.HashMap<>();
        for (ProductEntity p : repo.findByIdIn(ids)) {
            docs.put(p.getId(), textBuilder.build(p));
        }
        return docs;
    }

    public List<RecommendedItem> fetchCandidates(String query, Map<String, Object> slots,
                                                 SessionScope scope, int topN) {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(slots);
        if ("跑鞋".equals(slots.get("category"))) {
            f = SqlFilterBuilder.merge(f, SqlFilterBuilder.runningShoeFilter(slots));
        }
        // 叠加 scope 过滤（platformWide 时返回空片段，不影响原逻辑）
        f = SqlFilterBuilder.merge(f, scopeFilterBuilder.build(scope));

        List<ProductVectorService.Hit> hits = vector.search(query, f.clause(), f.params(), topN);
        if (hits.isEmpty()) return List.of();

        // 详情回填：多商户 scope 过滤已随本地电商域移除（Smartore 单店目录）
        Map<Long, ProductEntity> map = new HashMap<>();
        repo.findByIdIn(hits.stream().map(ProductVectorService.Hit::id).toList())
                .forEach(p -> map.put(p.getId(), p));

        // 初值用真实余弦相似度（不再是排名的线性代理），重排层的加减分才有正确量纲
        List<RecommendedItem> out = new ArrayList<>();
        for (ProductVectorService.Hit hit : hits) {
            ProductEntity p = map.get(hit.id());
            if (p == null) continue;  // 被 scope 过滤掉的 id，这里自然丢弃
            out.add(new RecommendedItem(p.getId(), p.getName(), p.getPrice(),
                    null, hit.similarity(),
                    p.getAttributes() == null ? Map.of() : p.getAttributes()));
        }
        return out;
    }
}