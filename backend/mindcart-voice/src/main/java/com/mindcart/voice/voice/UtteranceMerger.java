package com.mindcart.voice.voice;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ASR 分段终稿 → 编排触发的合并缓冲。
 * <p>
 * 背景：服务端 VAD 按静音分段，用户长句在逗号处停顿 400ms 即产生一个"终稿"，
 * 若每个终稿都立即触发完整编排，用户会收到对半句话的语音回复、剩余半句再触发第二轮
 * （E2E 实测首个 ASR 终稿早于推流结束约 -0.44s，即对片段的过快响应）。
 * <p>
 * 策略：终稿进入缓冲并调度延迟触发；延迟窗口内又来新终稿 → 合并重调度；
 * 窗口内转写仍在增长（新 partial，用户还在说/同一句的第二段正在转写）→ **顺延**触发时刻，
 * 直到转写停止增长超过阈值才触发——等价于对文本流做端点检测。
 * <p>
 * 反例（曾导致 WS 链路零编排）：把 partial 当作"用户重新开始"而**取消**待触发——
 * omni 在段终稿之后仍会推送下一段的 partial（实测终稿后 ~360ms 就有新 partial），
 * 取消后既无终稿也无触发，编排彻底不执行。
 * delay=0 时退化为逐段立即触发（兼容旧行为，不做任何合并）。
 * <p>
 * 触发内容只含**已定稿段落**：in-flight partial 不进入本次编排（它还没定稿，
 * 随后会作为该段终稿再次到达并进入下一次合并），避免同一段文本被播报两次。
 * <p>
 * 线程模型：回调可能来自 SDK 的 OkHttp 线程，触发回调运行在调度线程——
 * 全部状态访问经 synchronized(this) 保护。
 */
public class UtteranceMerger implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final long delayMs;
    private final Consumer<String> fire;
    private final StringBuilder pending = new StringBuilder();
    private ScheduledFuture<?> task;

    public UtteranceMerger(ScheduledExecutorService scheduler, long delayMs, Consumer<String> fire) {
        this.scheduler = scheduler;
        this.delayMs = Math.max(0, delayMs);
        this.fire = fire;
    }

    /**
     * 一个 VAD 分段终稿。
     * delay=0 时逐段立即触发（兼容旧行为，不做合并）；delay>0 时并入待发文本并重调度触发。
     * 回调在线程外调用，避免持锁执行编排提交。
     */
    public void onFinal(String segmentText) {
        if (segmentText == null || segmentText.isBlank()) return;
        String immediate = null;
        synchronized (this) {
            if (delayMs == 0) {                 // 兼容旧行为：逐段立即触发，不合并
                pending.setLength(0);
                immediate = segmentText.trim();
            } else {
                if (pending.length() > 0) pending.append("，");
                pending.append(segmentText.trim());
                schedule();
            }
        }
        if (immediate != null) fire.accept(immediate);
    }

    /** 有新的部分转写（同一句仍在增长）：**顺延**待触发时刻，等转写停下来再编排。 */
    public synchronized void onPartial(String partialText) {
        if (pending.length() == 0) return;        // 还没有定稿段落 → 无事可做
        if (partialText == null || partialText.isBlank()) return;
        schedule();                               // 顺延（不是取消：取消会让编排永不发生）
    }

    /** 有新的部分转写：语义同 {@link #onPartial(String)}（旧名保留，避免调用点散落）。 */
    public synchronized void onNewSpeech(String partialText) {
        onPartial(partialText);
    }

    private void schedule() {
        if (delayMs == 0) return;
        if (task != null) task.cancel(false);
        task = scheduler.schedule(this::flush, delayMs, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        String merged;
        synchronized (this) {
            merged = pending.toString();
            pending.setLength(0);
            task = null;
        }
        if (!merged.isBlank()) fire.accept(merged);
    }

    /** 会话关闭：撤销待触发并清空缓冲（未触发的半句话随连接终止丢弃）。 */
    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        pending.setLength(0);
    }
}
