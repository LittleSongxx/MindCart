package com.smartore.voice.service;

import com.smartore.voice.voice.TtsService;
import io.reactivex.Flowable;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CachedTtsPhrases {

    private final TtsService tts;
    private final Map<String, byte[]> cache = new HashMap<>();

    @PostConstruct
    public void preload() {
        String[] phrases = {
                "鸡哥给你挑了三款。",
                "好的，稍等。",
                "这就帮你找。"
        };
        // 预热是优化项不是依赖项：key 未配置/网络抖动/供应商故障时跳过即可，
        // 绝不能让 @PostConstruct 抛异常把整个应用启动炸掉——缺缓存只是首句多一次实时合成
        for (String p : phrases) {
            try {
                cache.put(p, synthesizeBlocking(p));
            } catch (Exception e) {
                log.warn("[WARMUP] TTS 短语预热失败，跳过该条：{}，原因：{}", p, e.getMessage());
            }
        }
        log.info("[Cost] TTS 短语缓存预热完成，共 {} 条", cache.size());
    }

    /** EmotionAgent 拿到第一个句子时调一下这个，命中就不走 TTS。 */
    public byte[] get(String phrase) { return cache.get(phrase); }

    private byte[] synthesizeBlocking(String text) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        tts.synthesize(Flowable.just(text)).blockingForEach(buf -> {
            byte[] arr = new byte[buf.remaining()];
            buf.get(arr);
            bos.write(arr);
        });
        return bos.toByteArray();
    }
}