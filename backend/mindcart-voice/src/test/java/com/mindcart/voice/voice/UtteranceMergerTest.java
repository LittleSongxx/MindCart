package com.mindcart.voice.voice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分段终稿合并器：长句在逗号停顿被 VAD 切成多段时，只对合并后的完整文本触发一次编排。
 * 关键回归：E2E 实测首个 ASR 终稿早于推流结束约 -0.44s——即对半句话发起了编排。
 */
class UtteranceMergerTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void down() {
        scheduler.shutdownNow();
    }

    @Test
    // 延迟窗口内的两个终稿合并成一次触发，文本用逗号连接
    void 合并窗口内的终稿只触发一次() throws Exception {
        List<String> fired = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        UtteranceMerger m = new UtteranceMerger(scheduler, 120, s -> {
            fired.add(s);
            latch.countDown();
        });
        m.onFinal("帮我推荐一双八百以内的跑鞋");
        Thread.sleep(40);           // 窗口内第二段
        m.onFinal("主要跑水泥路");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        Thread.sleep(150);          // 确认没有第二次触发
        assertEquals(1, fired.size());
        assertEquals("帮我推荐一双八百以内的跑鞋，主要跑水泥路", fired.get(0));
        m.close();
    }

    @Test
    // 顺延窗口内又来了新终稿：两段合并为一次触发，文本用逗号连接
    void 顺延窗口内新终稿继续合并() throws Exception {
        List<String> fired = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        UtteranceMerger m = new UtteranceMerger(scheduler, 120, s -> {
            fired.add(s);
            latch.countDown();
        });
        m.onFinal("帮我推荐跑鞋");
        Thread.sleep(40);
        m.onNewSpeech("白色的");     // 第二段正在转写 → 顺延
        m.onFinal("白色的");         // 第二段定稿 → 合并
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("帮我推荐跑鞋，白色的", fired.get(0));
        m.close();
    }

    @Test
    // 关键回归：终稿之后仍在到达的 partial 只能**顺延**触发，绝不能取消——
    // omni 在段终稿后 ~360ms 就会推送下一段 partial，取消会让编排永不发生（WS 链路曾零编排）
    void 终稿后的partial顺延而非取消() throws Exception {
        List<String> fired = new ArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        UtteranceMerger m = new UtteranceMerger(scheduler, 150, s -> {
            fired.add(s);
            latch.countDown();
        });
        m.onFinal("双八百以内的跑鞋");
        Thread.sleep(60);
        m.onPartial("是主要");        // 下一段正在转写 → 顺延
        Thread.sleep(60);
        m.onPartial("是主要跑水泥路");
        assertTrue(latch.await(2, TimeUnit.SECONDS), "顺延后应仍能触发");
        Thread.sleep(200);
        assertEquals(1, fired.size());
        assertEquals("双八百以内的跑鞋", fired.get(0), "触发内容只含已定稿段落");
        m.close();
    }

    @Test
    // 无定稿段落时的 partial 不产生任何调度（尚未说完就先到的 partial）
    void 无终稿时partial不触发() throws Exception {
        List<String> fired = new ArrayList<>();
        UtteranceMerger m = new UtteranceMerger(scheduler, 80, fired::add);
        m.onPartial("帮我推荐");
        Thread.sleep(200);
        assertTrue(fired.isEmpty(), "只有 partial、没有终稿 → 不应触发编排");
        m.close();
    }

    @Test
    // delay=0 退化为逐段立即触发（兼容旧行为）；空段不触发
    void 零延迟逐段触发且空段忽略() throws Exception {
        List<String> fired = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        UtteranceMerger m = new UtteranceMerger(scheduler, 0, s -> {
            fired.add(s);
            latch.countDown();
        });
        m.onFinal("  ");            // 空白段忽略
        m.onFinal("换一款");
        m.onFinal("太贵了");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, fired.size());
        m.close();
    }
}
