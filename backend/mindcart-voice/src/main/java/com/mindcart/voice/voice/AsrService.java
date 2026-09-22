package com.mindcart.voice.voice;

import com.alibaba.dashscope.audio.omni.OmniRealtimeAudioFormat;
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.google.gson.JsonObject;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 实时语音识别：走 omni 实时会话（modalities=text + 输入音频转写 + 服务端 VAD）。
 * <p>
 * 为什么不用 paraformer（Recognition）：当前网关 key 的原生 WS 端点不提供
 * paraformer/qwen3-asr 系模型（ModelNotFound）；omni 会话的服务端 VAD + 输入转写
 * 实测可稳定输出增量文本（事件字段 stash 为该句的累积转写）。
 * <p>
 * 定稿时机：服务端 VAD 的 speech_stopped 事件 + 600ms 尾部宽限（等最后一批增量，
 * voice-loop 评测曾因定稿过早吞掉尾词）；关麦兜底再延 1.8s，同一句只定稿一次。
 */
@Slf4j
@Component
public class AsrService {

    @Value("${agentscope.dashscope.api-key}")
    private String apiKey;

    @Value("${voice-shopping.voice.asr-model}")
    private String model;

    /** VAD 静音判停（ms）：越短用户说完到触发编排越快，太小有吞尾风险。 */
    @Value("${voice-shopping.asr.vad-silence-ms:300}")
    private int vadSilenceMs;

    /** speech_stopped 后的定稿宽限（ms）：等最后一批转写增量落地。 */
    @Value("${voice-shopping.asr.flush-grace-ms:400}")
    private int flushGraceMs;

    /** 定稿后的收敛等待（ms）：无新句则整体完成——多段语音（句内停顿被VAD分段）的收口判据。 */
    @Value("${voice-shopping.asr.settle-ms:700}")
    private int settleMs;

    /**
     * 一次语音识别会话。
     *
     * @param audioFlow 音频帧流（PCM 16k 16bit mono）
     * @param onPartial 实时回调：(该句累积文本, 是否为该句终稿)
     */
    public CompletableFuture<String> recognize(
            Flowable<ByteBuffer> audioFlow,
            BiConsumer<String, Boolean> onPartial) {

        CompletableFuture<String> done = new CompletableFuture<>();
        Schedulers.io().scheduleDirect(() -> run(audioFlow, onPartial, done));
        return done;
    }

    private void run(Flowable<ByteBuffer> audioFlow,
                     BiConsumer<String, Boolean> onPartial,
                     CompletableFuture<String> done) {

        Object lock = new Object();
        StringBuilder finals = new StringBuilder();
        String[] curItem = {null};
        // stash 是 omni 对"当前这一句"的累积转写，换句（新 item_id）时上一句即定稿
        StringBuilder[] curStash = {new StringBuilder()};
        // 当前 item 已定稿过的前缀：omni 的 stash 是**该 item 的累积文本**，定稿后同一 item
        // 若还有更晚的增量到达（尾段转写迟到），只补差集——否则整段会被重复入列
        StringBuilder flushedPrefix = new StringBuilder();
        // 定稿后允许新一句继续（多段语音：句内停顿被 VAD 分段），收敛判据见 settle
        AtomicBoolean flushed = new AtomicBoolean(false);
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        // callback 构造在 conv 之前，但定稿/收尾需要 conv：用 holder 解循环依赖
        AtomicReference<Runnable> harvestRef = new AtomicReference<>(() -> { });
        AtomicReference<Runnable> completeRef = new AtomicReference<>(() -> { });

        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);

        OmniRealtimeCallback cb = new OmniRealtimeCallback() {
            @Override
            public void onOpen() {
                opened.countDown();
            }

            @Override
            public void onEvent(JsonObject ev) {
                String type = ev.has("type") ? ev.get("type").getAsString() : "";
                if ("input_audio_buffer.speech_started".equals(type)) {
                    String itemId = ev.has("item_id") ? ev.get("item_id").getAsString() : "";
                    harvestRef.get().run();
                    synchronized (lock) {
                        curItem[0] = itemId;
                        flushedPrefix.setLength(0);   // 换句：前缀归零
                    }
                    flushed.set(false);      // 新一句开始，重置定稿守卫
                    lastActivity.set(System.currentTimeMillis());
                } else if ("input_audio_buffer.speech_stopped".equals(type)) {
                    // VAD 判定停说：宽限后定稿本段，并安排收敛检查——若没有新句跟上则整体完成
                    Schedulers.io().scheduleDirect(() -> {
                        try {
                            Thread.sleep(flushGraceMs);
                        } catch (InterruptedException ignored) {
                        }
                        long before = lastActivity.get();
                        if (flushed.compareAndSet(false, true)) {
                            harvestRef.get().run();    // 本段定稿（守卫保证同段不重复）
                        }
                        Schedulers.io().scheduleDirect(() -> {
                            try {
                                Thread.sleep(settleMs);
                            } catch (InterruptedException ignored) {
                            }
                            // 定稿后无新活动 → 收敛完成；有新句（多段语音）则继续等
                            if (lastActivity.get() == before && !done.isDone()) {
                                completeRef.get().run();
                            }
                        });
                    });
                } else if (type.equals("conversation.item.input_audio_transcription.delta")) {
                    String stash = ev.has("stash") ? ev.get("stash").getAsString() : "";
                    if (!stash.isEmpty()) {
                        synchronized (lock) {
                            curStash[0] = new StringBuilder(stash);
                        }
                        lastActivity.set(System.currentTimeMillis());
                        onPartial.accept(stash, false);
                    }
                } else if (type.contains("error")) {
                    log.error("[ASR] omni 会话错误：{}", ev);
                }
            }

            @Override
            public void onClose(int code, String reason) {
                closed.countDown();
            }

            @Override
            public void onError(Throwable t) {
                log.error("[ASR] omni 连接错误", t);
                closed.countDown();
            }
        };

        OmniRealtimeConversation conv = new OmniRealtimeConversation(
                OmniRealtimeParam.builder().model(model).apikey(apiKey).build(), cb);
        // 收割当前 stash 为本段终稿：只补"尚未定稿过的后缀"（迟到增量不重复整段）
        harvestRef.set(() -> {
            String emitted = null;
            synchronized (lock) {
                if (curStash[0].isEmpty()) return;
                String text = curStash[0].toString();
                String prefix = flushedPrefix.toString();
                emitted = (!prefix.isEmpty() && text.startsWith(prefix)) ? text.substring(prefix.length()) : text;
                flushedPrefix.setLength(0);
                flushedPrefix.append(text);
                curStash[0] = new StringBuilder();
            }
            if (!emitted.isEmpty()) {
                onPartial.accept(emitted, true);
                synchronized (lock) {
                    finals.append(emitted);
                }
            }
        });
        // 整体完成：**先无守卫收割**（尾段转写常晚于 speech_stopped 宽限到达，
        // 守卫会把它挡掉——这正是长句"只定稿前半句"的成因），再关会话完成 future
        completeRef.set(() -> {
            harvestRef.get().run();
            conv.close();
            if (!done.isDone()) {
                synchronized (lock) {
                    done.complete(finals.toString());
                }
            }
        });

        try {
            conv.connect();
            opened.await(5, TimeUnit.SECONDS);
            conv.updateSession(OmniRealtimeConfig.builder()
                    .modalities(List.of(OmniRealtimeModality.TEXT))
                    .inputAudioFormat(OmniRealtimeAudioFormat.PCM_16000HZ_MONO_16BIT)
                    .enableInputAudioTranscription(true)
                    .enableTurnDetection(true)
                    .turnDetectionType("server_vad")
                    .turnDetectionSilenceDurationMs(vadSilenceMs)
                    .build());
            Thread.sleep(200);   // 等 session.update 生效

            Disposable sub = audioFlow.subscribe(
                    b -> {
                        byte[] a = new byte[b.remaining()];
                        b.get(a);
                        conv.appendAudio(Base64.getEncoder().encodeToString(a));
                    },
                    e -> {
                        log.error("ASR 音频流错误", e);
                        done.completeExceptionally(e);
                    },
                    () -> {
                        // 垫 500ms 静音帮 VAD 收口；若 speech_stopped 已定稿则守卫拦住重复，
                        // 否则 1.8s 后兜底（保护"说完立刻关麦"的尾词不被吞）
                        conv.appendAudio(Base64.getEncoder().encodeToString(new byte[16000]));
                        Schedulers.io().scheduleDirect(() -> {
                            try {
                                Thread.sleep(1800);
                            } catch (InterruptedException ignored) {
                            }
                            completeRef.get().run();
                        });
                    });
            done.whenComplete((v, t) -> sub.dispose());

            // 兜底：连接彻底关闭后完成 future
            Schedulers.io().scheduleDirect(() -> {
                try {
                    closed.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                if (!done.isDone()) {
                    synchronized (lock) {
                        done.complete(finals.toString());
                    }
                }
            });
        } catch (Exception e) {
            log.error("ASR 错误", e);
            done.completeExceptionally(e);
        }
    }
}
