package com.smartore.voice.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** TTS 前置数字归一化：金额语境阿拉伯数字→中文读法；编号语境（如色号999）不转。 */
class TtsTextNormalizerTest {

    @Test
    // 中文读法手工验算
    void case01() {
        assertEquals("八百", TtsTextNormalizer.toChinese(800));
        assertEquals("五百九十九", TtsTextNormalizer.toChinese(599));
        assertEquals("一千", TtsTextNormalizer.toChinese(1000));
        assertEquals("一千八百九十九", TtsTextNormalizer.toChinese(1899));
        assertEquals("一百零五", TtsTextNormalizer.toChinese(105));
        assertEquals("一千零五", TtsTextNormalizer.toChinese(1005));
        assertEquals("一万", TtsTextNormalizer.toChinese(10000));
        assertEquals("两千", TtsTextNormalizer.toChinese(2000));
        assertEquals("十", TtsTextNormalizer.toChinese(10));
        assertEquals("十五", TtsTextNormalizer.toChinese(15));
        assertEquals("一百一十", TtsTextNormalizer.toChinese(110));
    }

    @Test
    // 只转金额语境_色号编号不动
    void case02() {
        assertEquals("八百以内的跑鞋", TtsTextNormalizer.normalize("800以内的跑鞋"));
        assertEquals("要五百九十九元", TtsTextNormalizer.normalize("要599元"));
        assertEquals("预算两千左右", TtsTextNormalizer.normalize("预算2000左右"));
        // #999 是色号：逐位读是对的，不许转成"九百九十九"
        assertEquals("Dior 999 经典正红", TtsTextNormalizer.normalize("Dior 999 经典正红"));
    }

    @Test
    // 无数字与null安全
    void case03() {
        assertEquals("这款比较合适", TtsTextNormalizer.normalize("这款比较合适"));
        assertNull(TtsTextNormalizer.normalize(null));
    }
}
