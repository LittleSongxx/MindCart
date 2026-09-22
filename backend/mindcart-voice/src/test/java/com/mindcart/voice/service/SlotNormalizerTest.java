package com.mindcart.voice.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** LLM 槽位值归一化：评测发现"蓝牙耳机/水泥路跑"这类自由文本导致下游精确过滤失效。 */
class SlotNormalizerTest {

    @org.junit.jupiter.api.BeforeAll
    static void loadCategories() {
        SlotNormalizer.refresh(java.util.List.of("跑鞋", "手表", "T恤", "耳机", "口红"), java.util.Map.of(
                "跑步鞋", "跑鞋", "运动鞋", "跑鞋", "蓝牙耳机", "耳机", "降噪耳机", "耳机",
                "机械表", "手表", "石英表", "手表", "智能手表", "手表", "腕表", "手表",
                "唇膏", "口红", "短袖", "T恤"));
    }

    @Test
    // 品类归一化对齐_category_l2
    void case01() {
        assertEquals("耳机", SlotNormalizer.normalizeCategory("蓝牙耳机"));
        assertEquals("耳机", SlotNormalizer.normalizeCategory("降噪耳机"));
        assertEquals("手表", SlotNormalizer.normalizeCategory("机械表"));
        assertEquals("手表", SlotNormalizer.normalizeCategory("智能手表"));
        assertEquals("手表", SlotNormalizer.normalizeCategory("腕表"));
        assertEquals("跑鞋", SlotNormalizer.normalizeCategory("跑步鞋"));
        assertEquals("跑鞋", SlotNormalizer.normalizeCategory("运动鞋"));
        assertEquals("口红", SlotNormalizer.normalizeCategory("唇膏"));
        assertEquals("跑鞋", SlotNormalizer.normalizeCategory("跑鞋"));
    }

    @Test
    // 识别不了的品类宁可移除也不过滤错杀
    void case02() {
        assertNull(SlotNormalizer.normalizeCategory("望远镜"));
        assertNull(SlotNormalizer.normalizeCategory(null));
    }

    @Test
    // 场景归一化对齐_scenario枚举
    void case03() {
        assertEquals("水泥路", SlotNormalizer.normalizeScenario("水泥路跑"));
        assertEquals("水泥路", SlotNormalizer.normalizeScenario("公路跑步"));
        assertEquals("越野", SlotNormalizer.normalizeScenario("跑山路"));
        assertEquals("水泥路", SlotNormalizer.normalizeScenario("马拉松比赛"));
        assertEquals("健身房", SlotNormalizer.normalizeScenario("室内跑步机"));
    }

    @Test
    // 无法安全映射的场景移除_交给向量语义
    void case04() {
        assertNull(SlotNormalizer.normalizeScenario("商务场合"));
        assertNull(SlotNormalizer.normalizeScenario("送礼"));
    }

    @Test
    // 整体_map归一化_不可识别字段被移除
    void case05() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("category", "蓝牙耳机");
        raw.put("scenario", "水泥路跑");
        raw.put("budget", 500);
        Map<String, Object> out = SlotNormalizer.normalize(raw);
        assertEquals("耳机", out.get("category"));
        assertEquals("水泥路", out.get("scenario"));
        assertEquals(500, out.get("budget"));
    }

    @Test
    // null安全
    void case06() {
        assertEquals(Map.of(), SlotNormalizer.normalize(null));
    }
}
