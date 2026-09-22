package com.mindcart.ai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合检索的行为契约（对应评测集里的真实 case）：
 * 1. 编号/型号类字面查询，BM25 命中含该字面值的文档（稠密向量的盲区）；
 * 2. RRF 融合两路名次后，双通道都靠前的文档排最前；
 * 3. 单通道独有结果仍能进入融合列表（不丢候选）。
 */
class HybridRetrievalServiceTest {

    private final HybridRetrievalService service = new HybridRetrievalService();

    @Test
    void tokenizerKeepsProductIdsWholeAndSplitsChineseBigram() {
        List<String> tokens = service.tokenize("SP202606260019 无线蓝牙耳机");
        assertTrue(tokens.contains("sp202606260019"), "编号应保留为整 token: " + tokens);
        assertTrue(tokens.contains("无线") && tokens.contains("线蓝"), "中文应按 bigram 切分: " + tokens);
    }

    @Test
    void bm25RanksExactTokenMatchFirst() {
        Map<String, String> docs = Map.of(
                "macbook", "Apple MacBook Air 13 英寸 M3 轻薄本 8G 256G",
                "词汇书", "考研英语高分词汇与真题解析 词汇 真题",
                "target", "商品编号 SP202606260019 索尼头戴式降噪耳机");
        List<String> ranked = service.bm25Rank("SP202606260019", docs);
        assertEquals("target", ranked.get(0), "字面精确命中应排第一");
    }

    @Test
    void rrfPrefersDualChannelAgreement() {
        // 稠密通道排 A>B>C；关键词通道排 B>D —— B 在两路都靠前，融合后应登顶
        List<String> fused = service.rrfFuse(
                List.of("A", "B", "C"),
                List.of("B", "D"));
        assertEquals("B", fused.get(0));
        assertTrue(fused.contains("A") && fused.contains("D"), "单通道独有结果不应丢失");
        assertTrue(fused.indexOf("A") < fused.indexOf("C"), "稠密排名信息应保留");
    }

    @Test
    void rrfHandlesEmptyChannel() {
        List<String> fused = service.rrfFuse(List.of("A", "B"), List.of());
        assertEquals(List.of("A", "B"), fused);
        assertTrue(service.rrfFuse(List.of(), List.of()).isEmpty());
    }
}
