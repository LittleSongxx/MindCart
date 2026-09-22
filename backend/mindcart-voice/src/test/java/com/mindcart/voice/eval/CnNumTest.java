package com.mindcart.voice.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 中文数字逆转换（CER 前数字形态归一化）——只覆盖本项目归一化器可产出的形态。 */
class CnNumTest {

    @Test
    // 解析手工验算
    void case01() {
        assertEquals(800, CnNum.parse("八百"));
        assertEquals(599, CnNum.parse("五百九十九"));
        assertEquals(1000, CnNum.parse("一千"));
        assertEquals(1899, CnNum.parse("一千八百九十九"));
        assertEquals(105, CnNum.parse("一百零五"));
        assertEquals(1005, CnNum.parse("一千零五"));
        assertEquals(2000, CnNum.parse("两千"));
        assertEquals(10000, CnNum.parse("一万"));
        assertEquals(15, CnNum.parse("十五"));
        assertEquals(110, CnNum.parse("一百一十"));
        assertEquals(20000, CnNum.parse("两万"));
    }

    @Test
    // 口语省略尾单位：口语价格几乎都是这种形态（"这块表一千六出头"）
    void case04() {
        assertEquals(1600, CnNum.parse("一千六"), "一千六 = 1600（此前按位解析得 1006）");
        assertEquals(250, CnNum.parse("两百五"));
        assertEquals(12000, CnNum.parse("一万二"));
        assertEquals(106, CnNum.parse("一百零六"), "含占位零 → 个位");
        assertEquals(1006, CnNum.parse("一千零六"));
        assertEquals(1600, CnNum.parse("一千六百"), "带尾单位仍按标准解析");
        assertEquals("这款1600出头", CnNum.toDigits("这款一千六出头"));
    }

    @Test
    // 超出约定形态返回_1
    void case02() {
        assertEquals(-1, CnNum.parse("亿"));
    }

    @Test
    // 文本级替换_单字数字不转_避免把专名里的字误转
    void case03() {
        assertEquals("800以内的跑鞋", CnNum.toDigits("八百以内的跑鞋"));
        assertEquals("要599元", CnNum.toDigits("要五百九十九元"));
        assertEquals("预算2000左右", CnNum.toDigits("预算两千左右"));
        // ≥2 字的数字串才转（"十全十美"含类外字"全"不会误匹配）
        assertEquals("15块", CnNum.toDigits("十五块"));
    }
}
