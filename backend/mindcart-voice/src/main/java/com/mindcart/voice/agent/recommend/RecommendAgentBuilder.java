package com.mindcart.voice.agent.recommend;

import com.mindcart.voice.agent.PromptLoader;
import com.mindcart.voice.memory.RedisMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecommendAgentBuilder {

    @Qualifier("mainChatModel")   // 推荐理由是门面文字，必须主模型
    private final Model mainModel;

    private final PromptLoader prompts;
    private final RedisMemoryFactory memoryFactory;

    public ReActAgent build(String sessionId) {
        return ReActAgent.builder()
                .name("recommend_agent")
                .model(mainModel)
                .sysPrompt(prompts.load("prompts/recommend-reason.txt"))
                .memory(memoryFactory.create(sessionId, "recommend"))
                .build();
    }
}
