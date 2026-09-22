package com.mindcart.voice.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 槽位值归一化：LLM 抽出的槽位是自由文本（"蓝牙耳机""水泥路跑""马拉松比赛"），
 * 而下游过滤是精确匹配（category_l2='耳机'、scenario 只认枚举值）。
 * 不归一化 = 场景过滤静默失效 / 品类过滤零结果。评测（intent-report）发现的真实缺口。
 */
public final class SlotNormalizer {

    /**
     * 合法品类集（DB 的 distinct category_l2，由 SlotNormalizerRefresher 启动时注入）。
     * 空时退化为内置兜底集，避免启动竞态期完全失效。
     */
    private static volatile java.util.Set<String> knownCategories = java.util.Set.of();

    /** 同义词→标准品类映射（yml：voice-shopping.normalize.category-synonyms）。 */
    private static volatile java.util.Map<String, String> categorySynonyms = java.util.Map.of();

    private SlotNormalizer() {
    }

    /** 由刷新器调用（启动 + 定时）：注入 DB 品类集与配置同义词。 */
    static void refresh(java.util.Collection<String> categories, java.util.Map<String, String> synonyms) {
        knownCategories = java.util.Set.copyOf(categories);
        categorySynonyms = java.util.Map.copyOf(synonyms);
    }

    static java.util.Set<String> knownCategories() {
        return knownCategories;
    }

    public static Map<String, Object> normalize(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> out = new HashMap<>(raw);
        Object cat = out.get("category");
        if (cat != null) {
            String c = normalizeCategory(cat.toString());
            if (c == null) out.remove("category"); else out.put("category", c);
        }
        Object sc = out.get("scenario");
        if (sc != null) {
            String s = normalizeScenario(sc.toString());
            if (s == null) out.remove("scenario"); else out.put("scenario", s);
        }
        return out;
    }

    /**
     * 对齐 DB 品类（category_l2），识别不了返回 null（宁可不过滤也不错杀）。
     * 通用规则：同义词表精确命中 → 品类词包含匹配（"蓝牙耳机"含"耳机"）→ 已是合法品类原样放行。
     */
    static String normalizeCategory(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        // 1) 同义词表（yml 配置，新品类零代码接入）
        String viaSyn = categorySynonyms.get(s);
        if (viaSyn != null && knownCategories.contains(viaSyn)) return viaSyn;
        // 2) 品类词包含匹配（短品类词优先，避免"耳机"抢先匹配长品类）
        String best = null;
        for (String cat : knownCategories) {
            if (!cat.isEmpty() && s.contains(cat) && (best == null || cat.length() > best.length())) {
                best = cat;
            }
        }
        if (best != null) return best;
        // 3) 原样合法则放行
        return knownCategories.contains(s) ? s : null;
    }

    /** 对齐 scenario_options（塑胶跑道/水泥路/越野/健身房/不固定），识别不了返回 null。 */
    static String normalizeScenario(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (s.contains("越野") || s.contains("山路")) return "越野";
        if (s.contains("水泥") || s.contains("公路") || s.contains("马路") || s.contains("柏油")
                || s.contains("马拉松") || s.contains("路跑") || s.contains("竞速")) return "水泥路";
        if (s.contains("跑道") || s.contains("塑胶") || s.contains("田径")) return "塑胶跑道";
        if (s.contains("健身") || s.contains("跑步机") || s.contains("室内")) return "健身房";
        if (s.contains("不固定") || s.contains("多种") || s.contains("都有")) return "不固定";
        return null;   // "马拉松比赛"这类无法安全映射的不硬塞，交给向量语义匹配
    }
}
