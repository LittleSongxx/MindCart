package com.mindcart.voice.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;


import java.util.List;

/** token 流按标点聚合成句：切分正确性、无标点尾句兜底。 */
class SentenceAggregatorTest {

    @Test
    // 按句末标点切分
    void case16() {
        Flux<String> tokens = Flux.just("你好", "。", "最近", "怎么样", "呀");
        StepVerifier.create(SentenceAggregator.aggregate(tokens))
                .expectNext("你好。")
                .expectNext("最近怎么样呀")     // 尾句无标点，由收尾 defer 兜底
                .verifyComplete();
    }

    @Test
    // 逗号也切_保证TTS首句尽早出声
    void case17() {
        Flux<String> tokens = Flux.just("好的", "，", "稍等", "，", "马上", "找。");
        StepVerifier.create(SentenceAggregator.aggregate(tokens))
                .expectNext("好的，")
                .expectNext("稍等，")
                .expectNext("马上找。")
                .verifyComplete();
    }

    @Test
    // 一个token里含多个句子
    void case18() {
        StepVerifier.create(SentenceAggregator.aggregate(Flux.just("第一句。第二句！")))
                .expectNext("第一句。")
                .expectNext("第二句！")
                .verifyComplete();
    }

    @Test
    // 空流返回空
    void case19() {
        StepVerifier.create(SentenceAggregator.aggregate(Flux.empty()))
                .verifyComplete();
    }

    @Test
    // 尾部孤立标点作为尾句输出
    void case20() {
        // "嗯。"正常切出；孤立的"，"位于 index 0 不满足 lastEnd>0，由尾句兜底单独输出，不吞内容
        StepVerifier.create(SentenceAggregator.aggregate(Flux.just("嗯。", "，")))
                .expectNext("嗯。")
                .expectNext("，")
                .verifyComplete();
    }

    @Test
    // 聚合不吞token_总字符守恒
    void case21() {
        List<String> tokens = List.of("abcdef", "。", "ghij", "kl", "，", "mnop");
        StepVerifier.create(SentenceAggregator.aggregate(Flux.fromIterable(tokens))
                        .collectList())
                .assertNext(sentences -> {
                    int total = String.join("", sentences).length();
                    assertEquals(String.join("", tokens).length(), total);
                })
                .verifyComplete();
    }
}
