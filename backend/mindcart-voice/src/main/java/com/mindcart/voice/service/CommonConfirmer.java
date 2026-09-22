package com.mindcart.voice.service;

import com.mindcart.voice.dto.Intent;
import com.mindcart.voice.dto.IntentResult;

import java.util.Map;
import java.util.Set;

final class CommonConfirmer {

    /**
     * 高频简短确认词——只收纯肯定词，固定短路为 ORDER_CONFIRM。
     * 语义随上下文变化的词（"换一个/下一个/再来/继续"= 换款/继续推荐，"好"在澄清语境=同意追问）
     * 不在此短路，交给意图 LLM 结合历史判别——此前"换一个"被短路成下单，
     * 而下单分支无指代可解析，形成"您想要哪一款"死路。
     */
    private static final Set<String> WORDS = Set.of(
            "嗯", "好", "好的", "对", "是", "行", "可以"
    );

    static final IntentResult CONFIRMER_INTENT =
            new IntentResult(Intent.ORDER_CONFIRM, Map.of("confirmer", true), 0.95);

    /**
     * 是否纯肯定确认词：整句可由词表词顺序切分完（允许多个连用，如"嗯好的"）。
     * 用切分而非整串命中——"嗯好的""好的好的"这类连用在真实语音里很常见，
     * 整串比对会漏（多轮评测 M09 即因此打到 LLM 被判闲聊）。
     */
    static boolean isCommonConfirmer(String utterance) {
        if (utterance == null) return false;
        String s = utterance.trim().replaceAll("[，。！？,.!?\\s]", "");
        if (s.isEmpty()) return false;
        int i = 0;
        while (i < s.length()) {
            String matched = null;
            for (String w : WORDS) {
                if (s.startsWith(w, i) && (matched == null || w.length() > matched.length())) {
                    matched = w;   // 最长优先，避免"好的"被"好"抢先切走留下孤字
                }
            }
            if (matched == null) return false;   // 出现词表外的字 → 交给意图 LLM 结合历史判别
            i += matched.length();
        }
        return true;
    }
}