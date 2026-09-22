package com.mindcart.voice.agent.clarify;

import com.mindcart.voice.agent.PromptLoader;
import com.mindcart.voice.memory.RedisMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClarifyAgentBuilder {

    @Qualifier("lightChatModel")   // 生成追问也不用主模型
    private final Model model;

    private final PromptLoader prompts;
    private final RedisMemoryFactory memoryFactory;

    public ReActAgent build(String sessionId) {
        return ReActAgent.builder()
                .name("clarify_agent")
                .model(model)
                .sysPrompt(prompts.load("prompts/clarify.txt"))
                .memory(memoryFactory.create(sessionId, "clarify"))
                .build();
    }
}
