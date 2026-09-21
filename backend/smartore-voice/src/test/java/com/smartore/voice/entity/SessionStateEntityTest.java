package com.smartore.voice.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归：session_state.slots 是 NOT NULL 列，而 Hibernate 插入时会把 null 字段
 * 显式写入（覆盖 DB 的 DEFAULT '{}'），导致新会话首次保存抛约束违例、
 * 整条流式链路中断（实测：客户端收不到任何音频）。
 * 实体必须保证 slots 永不为 null。
 */
class SessionStateEntityTest {

    @Test
    void slots默认非空() {
        assertNotNull(new SessionStateEntity().getSlots(),
                "slots 默认必须为空 Map——null 会在插入时触发 NOT NULL 约束违例");
    }

    @Test
    void slots可写() {
        SessionStateEntity e = new SessionStateEntity();
        e.setSlots(java.util.Map.of("category", "跑鞋"));
        assertEquals("跑鞋", e.getSlots().get("category"));
    }
}
