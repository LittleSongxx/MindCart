package com.smartore.voice.voice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TTS 前置文本归一化：把金额语境的阿拉伯数字转成中文读法。
 * 背景（voice-loop 评测发现）：omni TTS 把 "800" 逐位念成"八零零"，
 * ASR 原样返回导致回环 CER 虚高。
 * 只转"价格语境"的数字（后接 元/块/以内/左右 等），像 "#999" 色号这类
 * 编号语境必须保留逐位读法，不做全文硬转。
 */
public final class TtsTextNormalizer {

    private static final Pattern MONEY = Pattern.compile("(\\d{1,6})(?=(元|块钱|块|以内|以下|以上|左右|之间))");
    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] UNITS = {"", "十", "百", "千"};

    private TtsTextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = MONEY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, toChinese(Long.parseLong(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 整数转中文读法（一到六位，价格场景够用；不支持小数与负数，遇到原样返回）。 */
    static String toChinese(long n) {
        if (n < 0 || n > 999999) return String.valueOf(n);
        if (n < 10) return DIGITS[(int) n];
        if (n < 20) return "十" + (n % 10 == 0 ? "" : DIGITS[(int) (n % 10)]);
        String s = String.valueOf(n);
        if (s.length() <= 4) return group(s, 0);
        // 万位：拆成 万级 + 个级（group 已处理前导零折叠，如 10005 → 一万零五）
        String wan = s.substring(0, s.length() - 4);
        String rest = s.substring(s.length() - 4);
        String wanPart = (wan.equals("2") ? "两" : group(wan, 0)) + "万";
        if (Long.parseLong(rest) == 0) return wanPart;
        return wanPart + group(rest, 0);
    }

    /** 四位以内数字逐位组读法，连续前导零折叠成一个零（一千零五，不是一千零零五）。 */
    private static String group(String s, int depth) {
        if (s.isEmpty()) return "";
        if (s.charAt(0) == '0') {
            int i = 0;
            while (i < s.length() && s.charAt(i) == '0') i++;
            String rest = s.substring(i);
            if (rest.isEmpty()) return "";
            return "零" + group(rest, depth);
        }
        int len = s.length();
        if (len == 1) return DIGITS[s.charAt(0) - '0'];
        char head = s.charAt(0);
        String tail = s.substring(1);
        // 千/万位首位为 2 读"两"（两千、两万更符合口语；二百、二十保持"二"）
        String headDigit = DIGITS[head - '0'];
        if (head == '2' && len >= 4) headDigit = "两";
        String headPart = headDigit + UNITS[len - 1];
        if (Long.parseLong(tail) == 0) return headPart;
        return headPart + group(tail, depth);
    }
}
