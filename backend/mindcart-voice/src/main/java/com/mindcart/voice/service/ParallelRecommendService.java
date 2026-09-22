package com.mindcart.voice.service;

import com.mindcart.voice.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParallelRecommendService {

    private final UserProfileService profileService;
    private final RecommendCandidatesService candidates;
    private final ProfileReranker reranker;
    private final RecommendReasonService reasonService;
    private final SessionScopeCache scopeCache;
    private final ThreadPoolTaskExecutor recommendExecutor;
    private final SemanticRerankClient semanticRerank;
    private final com.mindcart.voice.integration.ProductLiveVerifier liveVerifier;

    /** 语义重排开关（默认关：+300~500ms 与语音首响冲突；文本场景/评测开启）。 */
    @Value("${voice-shopping.recommend.semantic-rerank.enabled:false}")
    private boolean semanticRerankEnabled;

    public RecommendResult recommend(String sessionId, Long userId,
                                     String utterance, Map<String, Object> slots) {
        return recommend(sessionId, userId, utterance, slots, true);
    }

    /**
     * @param withReasons false 时跳过推荐理由生成（同步调一次 LLM）。
     *                    流式链路里理由由情感 Agent 边生成边口播，这里再同步生成一遍
     *                    纯属多花一次 LLM 往返拖首响延迟，卡片展示名称/价格/属性已足够。
     */
    public RecommendResult recommend(String sessionId, Long userId,
                                     String utterance, Map<String, Object> slots,
                                     boolean withReasons) {

        SessionScope scope = scopeCache.get(sessionId);
        if (scope == null) scope = new SessionScope(userId, null, null); // 老会话兜底

        final SessionScope finalScope = scope; // lambda 要 effectively final

        CompletableFuture<UserProfileSnapshot> profileFuture =
                CompletableFuture.supplyAsync(() -> profileService.load(userId), recommendExecutor);

        CompletableFuture<List<RecommendedItem>> candidatesFuture =
                CompletableFuture.supplyAsync(() -> candidates.fetchCandidates(
                        utterance, slots, finalScope, 20), recommendExecutor);

        List<RecommendedItem> reranked = profileFuture
                .thenCombine(candidatesFuture, (profile, cands) -> reranker.rerank(cands, profile, slots))
                .join();

        if (reranked.isEmpty()) return new RecommendResult(List.of(), "empty");

        // 可选语义精排：规则重排后对 Top8 做富文档 cross-encoder 精排，取 Top3。
        // 仅同步/文本路径（withReasons=true）启用——语音流式路径首响优先（+2pp 不值 +0.4s）；
        // 富文档与向量库同源（消融 A5：贫文档 -4pp、富文档 +2pp）
        List<RecommendedItem> top3;
        if (semanticRerankEnabled && withReasons && reranked.size() > 3) {
            List<RecommendedItem> top8 = reranked.subList(0, Math.min(8, reranked.size()));
            var docs = candidates.buildRichDocs(
                    top8.stream().map(RecommendedItem::productId).toList());
            List<String> richDocs = top8.stream()
                    .map(it -> docs.getOrDefault(it.productId(), it.name())).toList();
            top3 = semanticRerank.rerank(utterance, top8, richDocs, 3);
        } else {
            top3 = reranked.stream().limit(3).toList();
        }
        // 出口前实时复核：剔除下架/无货，价格以 goods 实时数据为准（本地只是同步副本）
        top3 = liveVerifier.verify(top3);
        if (top3.isEmpty()) return new RecommendResult(List.of(), "empty");
        if (!withReasons) {
            return new RecommendResult(top3, "professional");
        }
        return new RecommendResult(
                reasonService.attachReasons(sessionId, utterance + "; " + slots, top3),
                "professional");
    }
}
