package com.smartore.voice.dto;

import java.util.Map;

/**
 * Intent Agent 的结构化输出。
 *
 * @param intent     分类到的意图
 * @param slots      槽位（经 SlotNormalizer 归一化后的值：category 对齐 category_l2、
 *                   scenario 对齐场景枚举），抽不到的填 null
 * @param confidence 模型给出的置信度 [0.0, 1.0]，JSON 解析失败降级时会填 0.3
 * @param rawSlots   LLM 原始抽取值（未归一化）。保留它是为了评测做"归一化组件"的
 *                   消融归因：同一响应里同时暴露归一化前后两份槽位
 */
public record IntentResult(
        Intent intent,
        Map<String, Object> slots,
        double confidence,
        Map<String, Object> rawSlots
) {
    public IntentResult(Intent intent, Map<String, Object> slots, double confidence) {
        this(intent, slots, confidence, slots);
    }
}
