package com.smartore.voice.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VectorLiteralTest {

    @Test
    // 基本格式
    void case12() {
        assertEquals("[1.0,2.5,-3.25]", VectorLiteral.of(new float[]{1.0f, 2.5f, -3.25f}));
    }

    @Test
    // 空向量
    void case13() {
        assertEquals("[]", VectorLiteral.of(new float[0]));
    }

    @Test
    // 单元素
    void case14() {
        assertEquals("[0.0]", VectorLiteral.of(new float[]{0.0f}));
    }

    @Test
    // 精度保留float语义
    void case15() {
        // float 的 0.1 打出来是 0.1（float→字符串按 float 精度），pgvector 侧再解析回 float，往返无损
        String s = VectorLiteral.of(new float[]{0.1f});
        assertEquals(0.1f, Float.parseFloat(s.substring(1, s.length() - 1)), 1e-8);
    }
}
