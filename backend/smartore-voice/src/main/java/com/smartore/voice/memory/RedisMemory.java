package com.smartore.voice.memory;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Redis 背书的 Agent 记忆：替代 InMemoryMemory，使同一 session 的 Agent 上下文
 * 可跨实例恢复（语音链路多实例化的前提——WS  sticky 路由下实例重启/漂移后，
 * 另一实例能用 Redis 里的记忆重建 Agent，用户无感）。
 * <p>
 * 存储模型：一个 Redis List 存一个 (sessionId, agent) 的消息序列，
 * 元素为 AgentScope JsonCodec 序列化的 Msg；写操作刷新 TTL（滑动过期，与会话同寿命）。
 * <p>
 * 失败语义：fail-soft。记忆是增强层不是关键路径——Redis 抖动时 getMessages
 * 返回空表、写操作丢弃，LLM 调用照常进行（意图 Agent 本就靠 Prompt 注入历史，
 * 情感/推荐 Agent 只是暂时失去跨轮连续性），不打断语音主链路。
 */
@Slf4j
public class RedisMemory implements Memory {

    /** deleteMessage 的墓碑值：LSET 标记后 LREM 删除（Redis List 无按下标删除原语） */
    private static final String TOMBSTONE = "__vs_deleted__";

    private final StringRedisTemplate redis;
    private final String key;
    private final Duration ttl;
    private final JsonCodec codec = JsonUtils.getJsonCodec();

    public RedisMemory(StringRedisTemplate redis, String sessionId, String agentName, Duration ttl) {
        this.redis = redis;
        this.key = "vs:agent_mem:" + sessionId + ":" + agentName;
        this.ttl = ttl;
    }

    @Override
    public void addMessage(Msg msg) {
        try {
            redis.opsForList().rightPush(key, codec.toJson(msg));
            redis.expire(key, ttl);
        } catch (Exception e) {
            log.warn("[RedisMemory] 写入失败丢弃（不影响主链路）key={}: {}", key, e.toString());
        }
    }

    @Override
    public List<Msg> getMessages() {
        try {
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw == null) return List.of();
            return raw.stream()
                    .filter(s -> !TOMBSTONE.equals(s))
                    .map(s -> codec.fromJson(s, Msg.class))
                    .toList();
        } catch (Exception e) {
            log.warn("[RedisMemory] 读取失败降级为空记忆 key={}: {}", key, e.toString());
            return List.of();
        }
    }

    @Override
    public void deleteMessage(int index) {
        try {
            redis.opsForList().set(key, index, TOMBSTONE);
            redis.opsForList().remove(key, 1, TOMBSTONE);
        } catch (Exception e) {
            log.warn("[RedisMemory] 删除失败丢弃 key={} index={}: {}", key, index, e.toString());
        }
    }

    @Override
    public void clear() {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[RedisMemory] 清空失败丢弃 key={}: {}", key, e.toString());
        }
    }
}
