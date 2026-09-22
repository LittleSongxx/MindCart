package com.mindcart.voice.voice;

import reactor.core.publisher.Flux;

public class SentenceAggregator {

    /**
     * 输入：逐 token 的文本片段
     * 输出：按 。！？， 聚合后的"可合成单元"。
     * 缓冲区内出现多个切分点时按【第一个】切：每凑齐一个可合成单元就立刻放行，
     * 不让后面的内容把首句的出声时间拖晚（首响延迟是这条链路的核心指标）。
     */
    public static Flux<String> aggregate(Flux<String> tokens) {
        StringBuilder buf = new StringBuilder();
        return tokens.concatMap(token -> {
            buf.append(token);
            String s = buf.toString();
            int firstEnd = firstSentenceEnd(s);
            if (firstEnd >= 0) {
                String ready = s.substring(0, firstEnd + 1);
                buf.delete(0, firstEnd + 1);
                return Flux.just(ready);
            }
            return Flux.<String>empty();
        }).concatWith(Flux.defer(() -> {
            // 收尾：模型最后一句可能没有标点（"…再聊聊"）
            if (buf.length() > 0) {
                String tail = buf.toString();
                buf.setLength(0);
                return Flux.just(tail);
            }
            return Flux.empty();
        }));
    }

    private static int firstSentenceEnd(String s) {
        int min = -1;
        for (char c : new char[]{'。', '！', '？', '，'}) {
            int i = s.indexOf(c);
            if (i >= 0 && (min == -1 || i < min)) min = i;
        }
        return min;
    }
}