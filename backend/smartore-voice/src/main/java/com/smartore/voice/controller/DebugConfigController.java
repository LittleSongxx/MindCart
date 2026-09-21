package com.smartore.voice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评测支撑：暴露当前生效的模型与 ASR/编排参数，评测报告头部自动记录——
 * 此前报告硬编码模型名（e2e 曾写 qwen3.8-flash，实际已切 DeepSeek），与被测对象脱节。
 */
@RestController
@RequestMapping("/voice/debug/config")
@RequiredArgsConstructor
public class DebugConfigController {

    @Value("${voice-shopping.llm.light-model}")
    private String lightModel;

    @Value("${voice-shopping.llm.main-model}")
    private String mainModel;

    @Value("${voice-shopping.llm.enable-thinking:false}")
    private boolean enableThinking;

    @Value("${voice-shopping.voice.asr-model}")
    private String asrModel;

    @Value("${voice-shopping.voice.tts-model}")
    private String ttsModel;

    @Value("${voice-shopping.asr.vad-silence-ms:400}")
    private int vadSilenceMs;

    @Value("${voice-shopping.asr.flush-grace-ms:400}")
    private int flushGraceMs;

    @Value("${voice-shopping.asr.settle-ms:700}")
    private int settleMs;

    @Value("${voice-shopping.orch.sentence-merge-delay-ms:500}")
    private long sentenceMergeDelayMs;

    @PostMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "lightModel", lightModel,
                "mainModel", mainModel,
                "enableThinking", enableThinking,
                "asrModel", asrModel,
                "ttsModel", ttsModel,
                "vadSilenceMs", vadSilenceMs,
                "flushGraceMs", flushGraceMs,
                "settleMs", settleMs,
                "sentenceMergeDelayMs", sentenceMergeDelayMs);
    }
}
