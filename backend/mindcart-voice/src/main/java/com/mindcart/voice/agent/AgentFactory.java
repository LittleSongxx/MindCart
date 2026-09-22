package com.mindcart.voice.agent;

import com.mindcart.voice.agent.clarify.ClarifyAgentBuilder;
import com.mindcart.voice.agent.emotion.EmotionAgentBuilder;
import com.mindcart.voice.agent.intent.IntentAgentBuilder;
import com.mindcart.voice.agent.perspective.PerspectiveAgentBuilder;
import com.mindcart.voice.agent.recommend.RecommendAgentBuilder;
import io.agentscope.core.ReActAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

@Component
@RequiredArgsConstructor
public class AgentFactory {

    private final IntentAgentBuilder intentBuilder;
    private final ClarifyAgentBuilder clarifyBuilder;
    private final RecommendAgentBuilder recommendBuilder;
    private final EmotionAgentBuilder emotionBuilder;
    private final PerspectiveAgentBuilder perspectiveBuilder;

    private static final int MAX = 1000;
    private final Map<String, AgentSet> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentSet> e) {
                    return size() > MAX;
                }
            });

    public AgentSet get(String sessionId) {
        // Agent 实例仍是进程内 LRU 缓存（避免每轮重建），但记忆在 Redis：
        // 实例重启/漂移后另一实例重建的 Agent 能接上同一 session 的上下文
        return cache.computeIfAbsent(sessionId, sid -> new AgentSet(
                intentBuilder.build(sid),
                clarifyBuilder.build(sid),
                recommendBuilder.build(sid),
                emotionBuilder.build(sid)
        ));
    }

    public void remove(String sessionId) {
        cache.remove(sessionId);
    }

    /**
     * 每次调用生成一套全新的"多视角点评团"三人组，不做缓存。
     * <p>
     * 为什么不进 AgentSet：点评团走 15 节 MsgHub 旁路，用 try-with-resources 短生命周期，
     * 如果复用 Agent 实例，InMemoryMemory 会累积前几轮讨论内容，污染下次点评的上下文。
     */
    public PerspectiveTeam newPerspectiveTeam() {
        return new PerspectiveTeam(
                perspectiveBuilder.build("price_advisor", "prompts/perspective_price.txt"),
                perspectiveBuilder.build("pro_runner", "prompts/perspective_pro.txt"),
                perspectiveBuilder.build("beginner_buyer", "prompts/perspective_beginner.txt")
        );
    }

    public void forEachSession(java.util.function.BiConsumer<String, AgentSet> consumer) {
        synchronized (cache) {
            cache.forEach(consumer);
        }
    }

    public record AgentSet(
            ReActAgent intent,
            ReActAgent clarify,
            ReActAgent recommend,
            ReActAgent emotion
    ) {
    }

    public record PerspectiveTeam(
            ReActAgent priceAdvisor,
            ReActAgent proRunner,
            ReActAgent beginnerBuyer
    ) {
    }
}