package com.smartore.voice.service;

import com.smartore.voice.entity.SessionStateEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** "就买第二款"这类序数词/位置词指代消解的表驱动测试。 */
class OrderReferenceResolverTest {

    private final OrderReferenceResolver resolver = new OrderReferenceResolver();

    private SessionStateEntity state(List<Long> last) {
        SessionStateEntity s = new SessionStateEntity();
        s.setLastRecommendations(last);
        return s;
    }

    @Test
    // 无历史推荐时一律解析失败
    void case73() {
        assertTrue(resolver.resolve(state(null), "就买第二款").isEmpty());
        assertTrue(resolver.resolve(state(List.of()), "就买第二款").isEmpty());
    }

    @Test
    // 中文序数词
    void case74() {
        assertEquals(Optional.of(20L), resolver.resolve(state(List.of(10L, 20L, 30L)), "就买第二款"));
        assertEquals(Optional.of(10L), resolver.resolve(state(List.of(10L, 20L)), "第一款来一单"));
        assertEquals(Optional.of(30L), resolver.resolve(state(List.of(10L, 20L, 30L)), "第三款吧"));
    }

    @Test
    // 阿拉伯数字序数词与无"款"字
    void case75() {
        assertEquals(Optional.of(20L), resolver.resolve(state(List.of(10L, 20L)), "第2款"));
        assertEquals(Optional.of(30L), resolver.resolve(state(List.of(10L, 20L, 30L)), "来个3"));
    }

    @Test
    // 位置词_开头最后中间
    void case76() {
        assertEquals(Optional.of(10L), resolver.resolve(state(List.of(10L, 20L, 30L)), "就要开头那个"));
        assertEquals(Optional.of(30L), resolver.resolve(state(List.of(10L, 20L, 30L)), "最后那款"));
        assertEquals(Optional.of(20L), resolver.resolve(state(List.of(10L, 20L, 30L)), "中间的就行"));
    }

    @Test
    // 序数词越界回退为失败
    void case77() {
        // 历史只有 3 款，"第五款"越界 → 不猜，返回空由上游追问
        assertTrue(resolver.resolve(state(List.of(10L, 20L, 30L)), "就买第五款").isEmpty());
    }

    @Test
    // 无指代信息返回空
    void case78() {
        assertTrue(resolver.resolve(state(List.of(10L, 20L)), "帮我看看跑鞋").isEmpty());
    }
}
