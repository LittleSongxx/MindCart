package com.smartore.voice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartore.voice.agent.AgentFactory;
import com.smartore.voice.dto.ClarifyResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClarifyService {

    private final AgentFactory factory;
    private final ClarifyRuleService ruleService;
    private final ObjectMapper mapper;
    private final LlmGuard llmGuard;

    @org.springframework.beans.factory.annotation.Value("${voice-shopping.llm.call-timeout:8s}")
    private java.time.Duration llmTimeout;

    public ClarifyResult decide(String sessionId, String utterance, Map<String, Object> slots) {
        String category = (String) slots.get("category");
        List<String> missing = ruleService.missingSlots(category, slots);

        if (missing.isEmpty()) {
            return ClarifyResult.ready();
        }

        // 兜底：一次最多问 2 个字段，超过就只把最重要的两个送进 Prompt
        // missingSlots 的返回顺序已经是按优先级排好的（required 在前，niceToHave 在后）
        if (missing.size() > 2) {
            missing = missing.subList(0, 2);
        }

        ReActAgent agent = factory.get(sessionId).clarify();
        String userMsg = String.format(
                "用户原话：%s%n已知信息：%s%n缺失字段：%s",
                utterance, writeJson(slots), missing);

        // 熔断/超时/失败时降级为规则话术：直接按缺失字段追问，不阻塞语音链路
        Msg resp = llmGuard.call(sessionId,
                () -> agent.call(Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(userMsg)
                        .build()).block(llmTimeout),
                () -> null);
        if (resp == null || resp.getTextContent() == null || resp.getTextContent().isBlank()) {
            return ClarifyResult.ask("能再具体说说你的需求吗？比如预算或者用途。", missing);
        }

        return ClarifyResult.ask(resp.getTextContent().trim(), missing);
    }

    private String writeJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { return String.valueOf(o); }
    }
}