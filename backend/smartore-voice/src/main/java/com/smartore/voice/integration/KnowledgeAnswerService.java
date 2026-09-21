package com.smartore.voice.integration;

import com.smartore.common.result.Result;
import com.smartore.voice.dto.RecommendResult;
import com.smartore.voice.service.EmotionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 语音侧知识问答：把"店铺政策/售后/物流/发票"类问题接到 ai 域的知识库上。
 * <p>
 * 定位：语音此前对这类问题只能给固定兜底话术（OUT_OF_SCOPE），是能力缺口；
 * 现在在兜底分支里先做一次全库检索，命中阈值才作答（低置信宁可不答，避免编造），
 * 且回答必须依据检索到的资料（证据随上下文交给话术 Agent，禁止自由发挥）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAnswerService {

    private final KnowledgeClient knowledgeClient;
    private final EmotionService emotionService;

    @Value("${voice-shopping.knowledge.enabled:true}")
    private boolean enabled;

    @Value("${voice-shopping.knowledge.min-score:0.5}")
    private double minScore;

    /** 知识回答结果：answer 为口播话术；evidence 为引用资料（调试/评测可观） */
    public record Answer(String text, List<KnowledgeClient.KnowledgeHitVO> evidence) {}

    /**
     * 尝试用知识库回答；无命中或不可用时返回 null（调用方走原兜底话术）。
     */
    public Answer tryAnswer(String sessionId, String utterance) {
        if (!enabled) return null;
        try {
            Result<List<KnowledgeClient.KnowledgeHitVO>> r =
                    knowledgeClient.search(new KnowledgeClient.SearchRequest(utterance, 3));
            List<KnowledgeClient.KnowledgeHitVO> hits = (r != null && "200".equals(r.getCode()))
                    ? r.getData() : null;
            if (hits == null || hits.isEmpty()) return null;

            StringBuilder evidence = new StringBuilder();
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeClient.KnowledgeHitVO h = hits.get(i);
                evidence.append("资料").append(i + 1).append("：")
                        .append(h.getTitle() == null ? "" : h.getTitle()).append("\n")
                        .append(h.getContent() == null ? "" : h.getContent()).append("\n\n");
            }
            // 复用情感 Agent：把资料作为上下文注入（与"点评团意见"同一模式），
            // 提示词已要求口语化、简短；此处追加忠实性约束
            String context = utterance
                    + "\n\n【客服资料】（只依据这些资料回答，资料没有覆盖的就说稍后帮你确认，不要编造）\n"
                    + evidence
                    + "回答要求：用一到两句口语化中文，直接说清洗方式/时效/条件，不要罗列标题。";
            String speech = emotionService.wrap(sessionId, context,
                    "知识问答（店铺政策/售后类）", new RecommendResult(List.of(), "knowledge")).speechText();
            if (speech == null || speech.isBlank()) return null;
            log.info("[Knowledge] 命中 {} 条资料，已生成回答 sessionId={}", hits.size(), sessionId);
            return new Answer(speech, hits);
        } catch (Exception e) {
            log.warn("[Knowledge] 检索不可用（走原兜底话术）: {}", e.getMessage());
            return null;
        }
    }
}
