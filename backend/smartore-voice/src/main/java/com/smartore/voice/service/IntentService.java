package com.smartore.voice.service;

import com.smartore.voice.agent.AgentMemoryPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartore.voice.agent.AgentFactory;
import com.smartore.voice.dto.Intent;
import com.smartore.voice.dto.IntentResult;
import com.smartore.voice.memory.ShortTermMemory;
import com.smartore.voice.util.JsonSlicing;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final AgentFactory factory;
    private final ObjectMapper mapper;
    private final ShortTermMemory memory;
    private final IntentCache cache;
    private final AgentMemoryPolicy memoryPolicy;
    private final LlmGuard llmGuard;

    @org.springframework.beans.factory.annotation.Value("${voice-shopping.llm.call-timeout:8s}")
    private java.time.Duration llmTimeout;

    public IntentResult classify(String sessionId, String utterance) {
        // -1) 极短确认词直接走进程内常量，连 Redis 都省了
        if (CommonConfirmer.isCommonConfirmer(utterance)) {
            log.debug("[Intent] 命中 confirmer 短路 sessionId={} utterance={}", sessionId, utterance);
            return CommonConfirmer.CONFIRMER_INTENT;
        }

        // 0) 先查缓存——5 分钟内同一会话说同样的话，跳过 LLM
        IntentResult cached = cache.get(sessionId, utterance);
        if (cached != null) {
            log.debug("[Intent] 命中缓存 sessionId={} utterance={}", sessionId, utterance);
            return cached;
        }

        ReActAgent agent = factory.get(sessionId).intent();
        memoryPolicy.beforeIntentCall(agent);

        String history = memory.recent(sessionId, 3).stream()
                .map(t -> t.role() + ": " + t.text())
                .reduce((a, b) -> a + "\n" + b).orElse("（无历史）");

        String userInput = """
                最近 3 轮对话摘要：
                %s

                当前这一句：
                %s
                """.formatted(history, utterance);

        // 熔断/超时/失败时 resp 为 null → 走下方解析失败分支降级 OUT_OF_SCOPE（不写缓存）
        Msg resp = llmGuard.call(sessionId,
                () -> agent.call(Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(userInput)
                        .build()).block(llmTimeout),
                () -> null);

        IntentResult result;
        try {
            String json = JsonSlicing.object(resp.getTextContent());
            Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() {});
            Intent intent = Intent.valueOf((String) parsed.get("intent"));
            Map<String, Object> rawSlots = (Map<String, Object>) parsed.get("slots");
            double conf = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();
            // LLM 槽位是自由文本，先归一化再进下游（缓存也存归一化值）；
            // rawSlots 原样保留在结果里，供评测做归一化组件的消融归因
            result = new IntentResult(intent, SlotNormalizer.normalize(rawSlots), conf, rawSlots);
        } catch (Exception e) {
            log.warn("意图解析失败，降级为 OUT_OF_SCOPE：{}", resp == null ? "(LLM 无响应)" : resp.getTextContent(), e);
            result = new IntentResult(Intent.OUT_OF_SCOPE, Map.of(), 0.3);
        }

        // 1) 解析失败的 OUT_OF_SCOPE 不写缓存，避免一次抖动把所有"相似的话"都污染成越权
        if (result.intent() != Intent.OUT_OF_SCOPE) {
            cache.put(sessionId, utterance, result);
        }
        return result;
    }

}