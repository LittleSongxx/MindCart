package com.smartore.voice.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P0 否决点验证：AgentScope 1.0.11 在本仓库的 Spring Boot 3.5.16 依赖树下
 * 能否编译并对 OpenAI 兼容端点完成一次真实调用。
 *
 * 运行方式（需要真实 key，默认构建不跑）：
 *   mvn test -pl smartore-voice -P eval -Dtest=AgentScopeSmokeTest
 * key 复用 Smartore 底座的环境变量：SMARTORE_CHAT_BASE_URL / SMARTORE_CHAT_API_KEY / SMARTORE_CHAT_MODEL。
 */
@Tag("eval")
class AgentScopeSmokeTest {

    @Test
    void openAiCompatibleChatWorks() {
        String baseUrl = System.getenv("SMARTORE_CHAT_BASE_URL");
        String apiKey = System.getenv("SMARTORE_CHAT_API_KEY");
        String model = System.getenv("SMARTORE_CHAT_MODEL");
        assumeTrue(baseUrl != null && apiKey != null && model != null,
                "未配置 SMARTORE_CHAT_* 环境变量，跳过真实调用冒烟");

        OpenAIChatModel chatModel = OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .generateOptions(GenerateOptions.builder().build())
                .build();

        ReActAgent agent = ReActAgent.builder()
                .name("smoke_agent")
                .model(chatModel)
                .sysPrompt("你是测试 Agent，只回复用户原文。")
                .memory(new InMemoryMemory())
                .build();

        Msg resp = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("pong")
                .build()).block(Duration.ofSeconds(30));

        String text = resp == null ? "" : resp.getTextContent();
        System.out.println("AgentScope smoke response: " + text);
        assertFalse(text == null || text.isBlank(), "AgentScope 真实调用应返回非空文本");
    }
}
