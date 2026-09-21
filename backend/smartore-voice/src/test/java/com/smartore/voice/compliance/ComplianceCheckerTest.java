package com.smartore.voice.compliance;

import com.smartore.voice.dto.EmotionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 广告法绝对化用词软化 + 敏感词等长掩码。 */
class ComplianceCheckerTest {

    private ComplianceChecker checker;

    @BeforeEach
    void setUp() throws Exception {
        checker = new ComplianceChecker();
        Field f = ComplianceChecker.class.getDeclaredField("absolutePatterns");
        f.setAccessible(true);
        f.set(checker, List.of("最好", "第一", "保证", "绝对"));
        checker.init();   // 加载 classpath 敏感词表
    }

    @Test
    // 绝对化用词软化映射
    void case01() {
        EmotionResult out = checker.ensureCompliant("s1", 1L,
                new EmotionResult("这是最好的跑鞋，全网第一", List.of()));
        assertEquals("这是比较合适的跑鞋，全网常用", out.speechText());
    }

    @Test
    // 保证与绝对也替换
    void case02() {
        EmotionResult out = checker.ensureCompliant("s1", 1L,
                new EmotionResult("保证舒服，绝对值", List.of()));
        assertEquals("通常舒服，基本值", out.speechText());
    }

    @Test
    // 敏感词等长掩码
    void case03() {
        EmotionResult out = checker.ensureCompliant("s1", 1L,
                new EmotionResult("这家店不卖假货", List.of()));
        assertEquals("这家店不卖**", out.speechText());   // "假货"两个字 → 两个*
    }

    @Test
    // 干净文本原样通过_展示块保留
    void case04() {
        EmotionResult in = new EmotionResult("这款比较合适", List.of());
        EmotionResult out = checker.ensureCompliant("s1", 1L, in);
        assertSame(in.speechText(), out.speechText());
        assertTrue(out.displayBlocks().isEmpty());
    }

    @Test
    // null输入安全
    void case05() {
        assertNull(checker.ensureCompliant("s1", 1L, null));
    }
}
