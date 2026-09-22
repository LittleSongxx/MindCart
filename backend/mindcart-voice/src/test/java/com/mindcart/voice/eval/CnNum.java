package com.mindcart.voice.eval;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文数字 ↔ 阿拉伯数字 的 CER 前归一化：把文本中的中文数字串转成阿拉伯数字。
 * 用途：跨转写族 CER 时统一数字形态（TTS 念"八百"、SenseVoice ITN 输出"800"，
 * 不归一形态会把等价转写计为错误——这是 CER 评测的标准文本归一化步骤）。
 * 只需正确处理本项目 TtsTextNormalizer 能产出的形态（≤六位、零/两/十百千万）。
 */
public final class CnNum {

    private static final String D = "零一二两三四五六七八九十百千万";
    private static final Pattern RUN = Pattern.compile("[" + D + "]{2,}");

    private CnNum() {
    }

    /** 把文本中的中文数字串（≥2 字符）转换为阿拉伯数字；解析不了的串原样保留。 */
    public static String toDigits(String s) {
        if (s == null || s.isEmpty()) return s;
        Matcher m = RUN.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            long v = parse(m.group());
            m.appendReplacement(out, v > 0 ? String.valueOf(v) : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 中文数字解析；非法返回 -1。
     * 口语省略尾单位按下一级量级补全："一千六"=1600、"两百五"=250、"一万二"=12000——
     * 语音价格几乎都是这种形态（此前按位解析得 1006，把真陈述判成失实）。
     */
    static long parse(String run) {
        long value = 0, current = 0;
        boolean any = false;
        long lastUnit = 0;             // 最近一次量级，用于省略单位的补全
        boolean zeroAfterUnit = false; // 出现"零"→ 后续按个位（"一百零六"=106）
        for (char c : run.toCharArray()) {
            switch (c) {
                case '零' -> { if (lastUnit > 0) zeroAfterUnit = true; }
                case '一' -> { current = current * 10 + 1; any = true; }
                case '二', '两' -> { current = current * 10 + 2; any = true; }
                case '三' -> { current = current * 10 + 3; any = true; }
                case '四' -> { current = current * 10 + 4; any = true; }
                case '五' -> { current = current * 10 + 5; any = true; }
                case '六' -> { current = current * 10 + 6; any = true; }
                case '七' -> { current = current * 10 + 7; any = true; }
                case '八' -> { current = current * 10 + 8; any = true; }
                case '九' -> { current = current * 10 + 9; any = true; }
                case '十' -> { value += (current == 0 ? 1 : current) * 10; current = 0; any = true; lastUnit = 10; zeroAfterUnit = false; }
                case '百' -> { value += (current == 0 ? 1 : current) * 100; current = 0; any = true; lastUnit = 100; zeroAfterUnit = false; }
                case '千' -> { value += (current == 0 ? 1 : current) * 1000; current = 0; any = true; lastUnit = 1000; zeroAfterUnit = false; }
                case '万' -> { value = (value + current) * 10000; current = 0; any = true; lastUnit = 10000; zeroAfterUnit = false; }
                default -> { return -1; }
            }
            if (current > 9999 || value > 999999) return -1;   // 超出约定形态
        }
        if (!any) return -1;
        if (lastUnit >= 100 && !zeroAfterUnit && current > 0 && current < 10) {
            return value + current * (lastUnit / 10);         // 口语省略尾单位
        }
        return value + current;
    }
}
