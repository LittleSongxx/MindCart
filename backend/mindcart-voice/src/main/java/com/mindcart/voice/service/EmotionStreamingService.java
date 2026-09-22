package com.mindcart.voice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcart.voice.agent.AgentFactory;
import com.mindcart.voice.dto.RecommendResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmotionStreamingService {

    private final AgentFactory factory;
    private final SessionMoodDetector moodDetector;
    private final ObjectMapper mapper;
    private final LlmGuard llmGuard;

    @org.springframework.beans.factory.annotation.Value("${voice-shopping.llm.stream-timeout:60s}")
    private java.time.Duration streamTimeout;

    public Flux<String> streamWrap(String sessionId, String userUtterance, RecommendResult rec) {
        String mood = moodDetector.detect(sessionId, userUtterance);
        Map<String, Object> input = Map.of(
                "userUtterance", userUtterance,
                "sessionMood", mood,
                "userNeeds", userUtterance,
                "products", rec.items()
        );

        ReActAgent agent = factory.get(sessionId).emotion();
        try {
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(mapper.writeValueAsString(input))
                    .build();
            return agent.stream(userMsg)
                    // 只要 LLM 的 token 流，工具调用之类的事件不进 TTS 通道
                    .filter(e -> e.getType() == EventType.REASONING)
                    .map(e -> e.getMessage().getTextContent())
                    // ReActAgent 在 reasoning 跑完后会把"完整 message"再吐一次，
                    // 表现为流末尾出现一段长 chunk 包含全文（甚至带 JSON 外壳的旧版回放）。
                    // 这里做一道去重：只接受 delta（与已积累内容不重叠的新片段）。
                    .scan(new String[]{"", ""}, (state, cur) -> {
                        String acc = state[1];
                        if (cur == null || cur.isEmpty() || cur.equals(acc) || acc.endsWith(cur)) {
                            return new String[]{"", acc};
                        }
                        // 模型若一次性返回完整版（cur 已包含了 acc），算出 delta 部分
                        if (!acc.isEmpty() && cur.startsWith(acc)) {
                            return new String[]{cur.substring(acc.length()), cur};
                        }
                        // 正常 delta：直接追加
                        return new String[]{cur, acc + cur};
                    })
                    .skip(1)
                    .map(s -> s[0])
                    .filter(s -> !s.isEmpty())
                    // 流式整体硬超时：LLM 停滞不再无界挂起，超时即结束文本流（TTS 自然收尾）
                    .timeout(streamTimeout)
                    .onErrorResume(e -> {
                        llmGuard.onStreamError(e);
                        return Flux.empty();
                    });
        } catch (Exception e) {
            return Flux.error(e);
        }
    }
}