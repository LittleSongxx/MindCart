package com.smartore.ai.integration;

import com.smartore.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 语音域只读契约（集群内 Feign）：让文本 AI 链路看到语音通道发生的事。
 * 与 goods/trade 的 internal 契约同规格：只读、带内部令牌、网关不可达。
 */
@FeignClient(name = "smartore-voice", contextId = "aiVoiceClient")
public interface VoiceFeignClient {

    @GetMapping("/internal/voice/user-summary/{userId}")
    Result<Map<String, Object>> userSummary(@PathVariable("userId") Long userId,
                                            @RequestParam(value = "limit", defaultValue = "5") Integer limit);

    /** 会话流水（AI 需要引用具体对话时使用；由 voice 侧做归属校验） */
    @GetMapping("/internal/voice/sessions/{sessionId}/messages")
    Result<List<Map<String, Object>>> sessionMessages(@PathVariable("sessionId") String sessionId,
                                                      @RequestParam(value = "limit", defaultValue = "10") Integer limit);
}
