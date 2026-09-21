package com.smartore.voice.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 品类级必填槽位规则（required-slots.yml 外置，已按 Smartore 类目校准）。 */
class ClarifyRuleServiceTest {

    private final ClarifyRuleService service;

    ClarifyRuleServiceTest() throws Exception {
        this.service = new ClarifyRuleService();
    }

    @Test
    // 运动鞋服缺场景_需要追问
    void case60() {
        var missing = service.missingSlots("运动鞋服", Map.of("category", "运动鞋服", "budget", 800));
        assertEquals(java.util.List.of("scenario"), missing);
    }

    @Test
    // 运动鞋服三项齐全_不追问
    void case61() {
        assertTrue(service.missingSlots("运动鞋服",
                Map.of("category", "运动鞋服", "budget", 800, "scenario", "跑步")).isEmpty());
    }

    @Test
    // 笔记本电脑必填预算
    void case62() {
        assertEquals(java.util.List.of("budget"),
                service.missingSlots("笔记本电脑", Map.of("category", "笔记本电脑")));
    }

    @Test
    // 智能穿戴只要品类加预算
    void case63() {
        assertTrue(service.missingSlots("智能穿戴",
                Map.of("category", "智能穿戴", "budget", 3000)).isEmpty());
    }

    @Test
    // 未知品类走default规则
    void case64() {
        assertEquals(java.util.List.of("budget"),
                service.missingSlots("望远镜", Map.of("category", "望远镜")));
    }

    @Test
    // 全空槽位返回全部必填
    void case65() {
        assertEquals(java.util.List.of("category", "scenario", "budget"),
                service.missingSlots("运动鞋服", Map.of()));
    }
}
