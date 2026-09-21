package com.smartore.voice.service;

import com.smartore.voice.agent.AgentMemoryPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartore.voice.agent.AgentFactory;
import com.smartore.voice.dto.RecommendedItem;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import com.smartore.voice.util.JsonSlicing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendReasonService {

    private final AgentFactory factory;
    private final ObjectMapper mapper;
    private final AgentMemoryPolicy memoryPolicy;
    private final LlmGuard llmGuard;

    @org.springframework.beans.factory.annotation.Value("${voice-shopping.llm.call-timeout:8s}")
    private java.time.Duration llmTimeout;

    public List<RecommendedItem> attachReasons(String sessionId,
                                               String userNeeds,
                                               List<RecommendedItem> products) {
        if (products.isEmpty()) return products;
        ReActAgent agent = factory.get(sessionId).recommend();
        memoryPolicy.beforeRecommendCall(agent);

        Map<String, Object> input = Map.of(
                "userNeeds", userNeeds,
                "products", products.stream().map(p -> Map.of(
                        "productId", p.productId(),
                        "name", p.name(),
                        "price", p.price(),
                        "attributes", p.attributes()
                )).toList()
        );

        try {
            // Jackson2 的 writeValueAsString 是受检异常，提到 lambda 外序列化
            String inputJson = mapper.writeValueAsString(input);
            // 熔断/超时/失败时 resp 为 null → 解析失败走 catch，降级为空理由
            Msg resp = llmGuard.call(sessionId,
                    () -> agent.call(Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(inputJson)
                            .build()).block(llmTimeout),
                    () -> null);

            String json = JsonSlicing.array(resp.getTextContent());
            List<Map<String, Object>> out = mapper.readValue(json, new TypeReference<>() {});
            Map<Long, String> reasonMap = new HashMap<>();
            for (Map<String, Object> r : out) {
                Long pid = ((Number) r.get("productId")).longValue();
                reasonMap.put(pid, (String) r.get("reason"));
            }
            return products.stream()
                    .map(p -> p.withReason(reasonMap.getOrDefault(p.productId(), "")))
                    .toList();
        } catch (Exception e) {
            log.warn("推荐理由生成失败，降级为空理由", e);
            return products;
        }
    }
}