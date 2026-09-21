package com.smartore.voice.service;

import com.smartore.voice.dto.SessionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 槽位→SQL 过滤片段翻译：正确性、参数化占位（防注入）、条件组合、场景映射。 */
class SqlFilterBuilderTest {

    @Test
    // null槽位返回空过滤
    void case46() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(null);
        assertEquals("", f.clause());
        assertTrue(f.params().isEmpty());
    }

    @Test
    // 单条件_品类
    void case47() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(Map.of("category", "跑鞋"));
        assertEquals("category_l2 = ?", f.clause());
        assertEquals(List.of("跑鞋"), f.params());
    }

    @Test
    // 单条件_预算上限
    void case48() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(Map.of("budget", 500));
        assertEquals("price <= ?", f.clause());
        assertEquals(500.0, (Double) f.params().get(0));
    }

    @Test
    // 字符串型预算被忽略_防脏槽位进SQL
    void case49() {
        // LLM 抽出的槽位可能是字符串；只接受 Number，防止 "500 OR 1=1" 之类进过滤
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(Map.of("budget", "500"));
        assertEquals("", f.clause());
    }

    @Test
    // 价格下限_对比场景
    void case50() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(Map.of("priceMin", 800));
        assertEquals("price >= ?", f.clause());
    }

    @Test
    // 性别过滤_带unisex兜底
    void case51() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(Map.of("gender", "female"));
        // IS NULL 兜底：未标注 gender 的品类（口红/耳机）不能被排除（否则性别指代场景零结果）
        assertEquals("(attributes->>'gender' IS NULL OR attributes->>'gender' IN (?, 'unisex'))", f.clause());
        assertEquals(List.of("female"), f.params());
    }

    @Test
    // 组合条件_占位符顺序与参数顺序一致
    void case52() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(Map.of(
                "category", "跑鞋", "budget", 800, "brand", "Nike"));
        assertEquals("category_l2 = ? AND price <= ? AND brand = ?", f.clause());
        assertEquals(List.of("跑鞋", 800.0, "Nike"), f.params());
    }

    @Test
    // 排除商品_占位符数量与参数一致
    void case53() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(
                Map.of("excludeProductIds", List.of(1L, 2L, 3L)));
        assertEquals("id NOT IN (?, ?, ?)", f.clause());
        assertEquals(3, f.params().size());
    }

    @Test
    // 恶意值只进参数不进SQL_防注入
    void case54() {
        String evil = "跑鞋' OR '1'='1";
        SqlFilterBuilder.Filter f = SqlFilterBuilder.fromSlots(Map.of("category", evil));
        assertEquals("category_l2 = ?", f.clause());       // 子句里永远只有占位符
        assertEquals(evil, f.params().get(0));             // 恶意值作为参数原样绑定
        assertFalse(f.clause().contains("'"));            // 子句不含任何用户可控字面量
    }

    @Test
    // 跑鞋场景映射_水泥路排除越野鞋
    void case55() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.runningShoeFilter(Map.of("scenario", "水泥路"));
        assertTrue(f.clause().contains("cushion' IN ('high','medium')"));
        assertTrue(f.clause().contains("'road'"));
        assertTrue(f.clause().contains("IS NULL"));      // 未标注 terrain 的商品不误杀
    }

    @Test
    // 跑鞋场景映射_越野必须trail
    void case56() {
        SqlFilterBuilder.Filter f = SqlFilterBuilder.runningShoeFilter(Map.of("scenario", "越野"));
        assertEquals("attributes->>'cushion' = 'high' AND attributes->>'terrain' = 'trail'", f.clause());
    }

    @Test
    // 未识别场景与无场景返回空
    void case57() {
        assertEquals("", SqlFilterBuilder.runningShoeFilter(Map.of("scenario", "月球")).clause());
        assertEquals("", SqlFilterBuilder.runningShoeFilter(Map.of()).clause());
    }

    @Test
    // merge_空片段穿透_参数合并
    void case58() {
        SqlFilterBuilder.Filter a = SqlFilterBuilder.fromSlots(Map.of("category", "跑鞋"));
        SqlFilterBuilder.Filter merged = SqlFilterBuilder.merge(a, SqlFilterBuilder.merge(
                new SqlFilterBuilder.Filter("", List.of()),
                new SqlFilterBuilder.Filter("stock > 0", List.of())));
        assertEquals("category_l2 = ? AND stock > 0", merged.clause());
        assertEquals(List.of("跑鞋"), merged.params());
    }

    @Test
    // 商家隔离_IN占位符
    void case59() {
        ScopeFilterBuilder b = new ScopeFilterBuilder();
        assertEquals("", b.build(null).clause());
        assertEquals("", b.build(new SessionScope(1L, null, null)).clause());   // 全平台

        SqlFilterBuilder.Filter f = b.build(new SessionScope(1L, List.of(1L, 2L), null));
        assertEquals("merchant_id IN (?,?)", f.clause());
        assertEquals(List.of(1L, 2L), f.params());
    }
}
