package com.mindcart.voice.agent.emotion;

import com.mindcart.voice.agent.PromptLoader;
import com.mindcart.voice.memory.RedisMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmotionAgentBuilder {

    @Qualifier("mainChatModel")   // 主模型，文字质量要求高
    private final Model mainModel;

    private final PromptLoader prompts;
    private final RedisMemoryFactory memoryFactory;

    public ReActAgent build(String sessionId) {
        return ReActAgent.builder()
                .name("emotion_agent")
                .model(mainModel)
                .sysPrompt(prompts.load("prompts/emotion-merged.txt"))
                .memory(memoryFactory.create(sessionId, "emotion"))
                .build();
    }
}
