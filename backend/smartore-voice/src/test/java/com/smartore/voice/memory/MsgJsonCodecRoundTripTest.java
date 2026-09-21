package com.smartore.voice.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisMemory 的存储假设验证：AgentScope 的 JsonCodec 必须能把 Msg（含 TextBlock）
 * 序列化成 JSON 并读回成 Msg——多实例记忆恢复的整条链路都建立在这个假设上。
 */
class MsgJsonCodecRoundTripTest {

    private final JsonCodec codec = JsonUtils.getJsonCodec();

    @Test
    void msg_with_text_content_round_trips() {
        Msg original = Msg.builder()
                .name("emotion_agent")
                .role(MsgRole.ASSISTANT)
                .textContent("好，鸡哥给你挑了几款。")
                .build();

        String json = codec.toJson(original);
        Msg restored = codec.fromJson(json, Msg.class);

        assertThat(restored).isNotNull();
        assertThat(restored.getTextContent()).isEqualTo("好，鸡哥给你挑了几款。");
        assertThat(restored.getRole()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(restored.getName()).isEqualTo("emotion_agent");
    }

    @Test
    void msg_with_metadata_round_trips() {
        Msg original = Msg.builder()
                .role(MsgRole.USER)
                .textContent("帮我找跑鞋")
                .metadata(java.util.Map.of("source", "test"))
                .build();

        Msg restored = codec.fromJson(codec.toJson(original), Msg.class);

        assertThat(restored.getTextContent()).isEqualTo("帮我找跑鞋");
        assertThat(restored.getMetadata()).containsEntry("source", "test");
    }
}
