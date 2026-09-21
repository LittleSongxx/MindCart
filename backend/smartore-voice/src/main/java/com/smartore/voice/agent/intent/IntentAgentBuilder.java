package com.smartore.voice.agent.intent;

import com.smartore.voice.agent.PromptLoader;
import com.smartore.voice.memory.RedisMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IntentAgentBuilder {

    @Qualifier("lightChatModel")
    private final Model lightChatModel;

    private final PromptLoader prompts;
    private final RedisMemoryFactory memoryFactory;

    public ReActAgent build(String sessionId) {
        String sys = prompts.load("prompts/intent.txt");
        return ReActAgent.builder()
                .name("intent_agent")
                .model(lightChatModel)
                .sysPrompt(sys)
                .memory(memoryFactory.create(sessionId, "intent"))
                .build();
    }
}
