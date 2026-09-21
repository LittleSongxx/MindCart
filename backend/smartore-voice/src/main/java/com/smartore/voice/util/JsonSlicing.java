package com.smartore.voice.util;

/**
 * LLM 输出的 JSON 切片：模型常在前言/围栏里夹带 JSON，这里抠出最外层对象或数组。
 * 之前 {@code IntentService} 与 {@code RecommendReasonService} 各有一份近似实现，
 * 统一到此处，避免两处裁剪规则漂移（一个按 {} 裁、一个按 [] 裁）。
 */
public final class JsonSlicing {

    private JsonSlicing() {
    }

    /** 抠出最外层 JSON 对象（{}）；找不到时原样返回（交由调用方的解析失败分支处理）。 */
    public static String object(String text) {
        return slice(text, '{', '}');
    }

    /** 抠出最外层 JSON 数组（[]）；找不到时原样返回。 */
    public static String array(String text) {
        return slice(text, '[', ']');
    }

    private static String slice(String text, char open, char close) {
        if (text == null) return "";
        int start = text.indexOf(open);
        int end = text.lastIndexOf(close);
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }
}
