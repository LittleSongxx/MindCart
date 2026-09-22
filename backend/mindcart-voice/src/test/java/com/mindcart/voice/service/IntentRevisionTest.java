package com.mindcart.voice.service;

import com.mindcart.voice.dto.Intent;
import com.mindcart.voice.dto.IntentResult;
import com.mindcart.voice.entity.SessionStateEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 意图分类之上的两层规则矫正：触发与不触发的边界。
 * 场景来源：qwen 对"便宜点/换一款"常误判 CLARIFY_NEEDED。
 */
class IntentRevisionTest {

    private SessionStateEntity state(String phase, List<Long> last, Map<String, Object> slots) {
        SessionStateEntity s = new SessionStateEntity();
        s.setPhase(phase);
        s.setLastRecommendations(last);
        s.setSlots(slots);
        return s;
    }

    // ===== 规则（1）：上轮已推荐 + 本轮有价格方向词 → 强制 PRODUCT_COMPARE =====

    @Test
    // 误判为澄清_被矫正为对比
    void case37() {
        SessionStateEntity st = state("RECOMMEND", List.of(1L, 2L), Map.of("category", "跑鞋"));
        IntentResult in = new IntentResult(Intent.CLARIFY_NEEDED,
                Map.of("priceDirection", "cheaper"), 0.6);
        IntentResult out = OrchestratorService.reviseIntentByContext(st, in);
        assertEquals(Intent.PRODUCT_COMPARE, out.intent());
        assertEquals("cheaper", out.slots().get("priceDirection"));
    }

    @Test
    // 判为推荐_同样矫正为对比
    void case38() {
        SessionStateEntity st = state("RECOMMEND", List.of(1L), null);
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of("priceDirection", "expensive"), 0.8));
        assertEquals(Intent.PRODUCT_COMPARE, out.intent());
    }

    @Test
    // 无历史推荐_不矫正
    void case39() {
        SessionStateEntity st = state("RECOMMEND", null, null);
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.CLARIFY_NEEDED, Map.of("priceDirection", "cheaper"), 0.6));
        assertEquals(Intent.CLARIFY_NEEDED, out.intent());
    }

    @Test
    // 上轮不在推荐阶段_不矫正
    void case40() {
        SessionStateEntity st = state("CLARIFY", List.of(1L), null);
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.CLARIFY_NEEDED, Map.of("priceDirection", "cheaper"), 0.6));
        assertEquals(Intent.CLARIFY_NEEDED, out.intent());
    }

    @Test
    // 无价格方向词_不矫正
    void case41() {
        SessionStateEntity st = state("RECOMMEND", List.of(1L), null);
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.CLARIFY_NEEDED, Map.of(), 0.6));
        assertEquals(Intent.CLARIFY_NEEDED, out.intent());
    }

    @Test
    // 闲聊不受规则（1）影响
    void case42() {
        SessionStateEntity st = state("RECOMMEND", List.of(1L), null);
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.CHITCHAT, Map.of("priceDirection", "cheaper"), 0.6));
        assertEquals(Intent.CHITCHAT, out.intent());
    }

    // ===== 规则（2）：历史+本轮槽位合并后信息已足 → CLARIFY_NEEDED 强制改推荐 =====

    @Test
    // 信息已足_免追问直接推荐_槽位被合并
    void case43() {
        // 历史：品类+场景；本轮：预算 → 合并后齐全，不该再问
        SessionStateEntity st = state("CLARIFY", null, Map.of("category", "跑鞋", "scenario", "水泥路"));
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.CLARIFY_NEEDED, Map.of("budget", 800), 0.7));
        assertEquals(Intent.PRODUCT_RECOMMENDATION, out.intent());
        assertEquals("跑鞋", out.slots().get("category"));
        assertEquals(800, out.slots().get("budget"));
    }

    @Test
    // 只有品类缺锚点_仍需澄清
    void case44() {
        SessionStateEntity st = state("CLARIFY", null, Map.of("category", "跑鞋"));
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.CLARIFY_NEEDED, Map.of(), 0.7));
        assertEquals(Intent.CLARIFY_NEEDED, out.intent());
    }

    @Test
    // 历史槽位为null_仅靠本轮判断
    void case45() {
        SessionStateEntity st = state("CLARIFY", null, null);
        IntentResult out = OrchestratorService.reviseIntentByContext(st,
                new IntentResult(Intent.CLARIFY_NEEDED, Map.of("category", "手表", "budget", 2000), 0.7));
        assertEquals(Intent.PRODUCT_RECOMMENDATION, out.intent());
    }
}
