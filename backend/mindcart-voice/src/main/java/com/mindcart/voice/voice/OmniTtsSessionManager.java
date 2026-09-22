package com.mindcart.voice.voice;

import com.alibaba.dashscope.audio.omni.OmniRealtimeAudioFormat;
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * omni TTS 常驻会话管理：按 sessionId 复用 WebSocket 会话。
 * 背景（延迟实测）：每次 synthesize 新建会话的建立成本 ~0.9s；官方协议支持单会话多轮
 * response.create，探针实测复用后每句首音频仅 ~0.5s 且 4 连发转写零漂移。
 * <p>
 * 生命周期：轮次开始 prewarm() 后台建连（把握手移出关键路径）；每句复用；
 * 连续 resetEvery 次响应或任何错误后重建（防对话上下文累积污染朗读行为）；
 * LRU 上限 maxSessions，WS 断开时由调用方 close() 释放。
 * 并发：每会话一把锁串行化（事件回调走 volatile ctx，绝不碰锁，避免与 speak 互等）。
 */
@Slf4j
@Component
public class OmniTtsSessionManager {

    public static final String SYSTEM_SESSION = "__system__";

    @Value("${agentscope.dashscope.api-key}")
    private String apiKey;

    @Value("${voice-shopping.voice.tts-model}")
    private String model;

    @Value("${voice-shopping.voice.tts-voice}")
    private String voice;

    @Value("${voice-shopping.tts.session-reset-every:16}")
    private int resetEvery;

    @Value("${voice-shopping.tts.session-max:200}")
    private int sessionMax;

    private final Map<String, LiveSession> sessions = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LiveSession> eldest) {
                    boolean evict = size() > sessionMax;
                    if (evict) eldest.getValue().shutdown();
                    return evict;
                }
            });

    /** 轮次开始时调用：后台建连+配置会话，与意图/检索/话术并行。 */
    public void prewarm(String sessionId) {
        Schedulers.io().scheduleDirect(() -> {
            try {
                ensureSession(sessionId);
            } catch (Exception e) {
                log.warn("[TTS] prewarm 失败（首次合成时重建）：{}", e.getMessage());
            }
        });
    }

    public void close(String sessionId) {
        LiveSession s = sessions.remove(sessionId);
        if (s != null) s.shutdown();
    }

    /**
     * 优雅停机：进程关闭前主动释放全部常驻 omni 会话（每条都是一条到 DashScope 的
     * WS 长连接），配合 server.shutdown=graceful 的排空窗口，避免连接泄漏到超时。
     */
    @jakarta.annotation.PreDestroy
    public void shutdownAll() {
        log.info("[TTS] 优雅停机：释放全部 omni 会话（{} 条）", sessions.size());
        synchronized (sessions) {
            sessions.values().forEach(LiveSession::shutdown);
            sessions.clear();
        }
    }

    /**
     * 复用会话合成一句。任何会话层失败自动重建重试一次；再失败向调用方抛错。
     */
    public Flowable<ByteBuffer> speak(String sessionId, String text) {
        Flowable<ByteBuffer> flow = Flowable.create(emitter -> {
            try {
                LiveSession live = ensureSession(sessionId);
                int r = doSpeak(live, text, emitter);
                if (r == 0 && !emitter.isCancelled()) {
                    // 零输出失败（会话层问题）：重建后重试一次；已出声的失败(-1)不重试防重复拼接
                    live = rebuild(sessionId);
                    r = doSpeak(live, text, emitter);
                    if (r != 1 && !emitter.isCancelled()) {
                        emitter.onError(new IllegalStateException("TTS 会话重试仍失败 sessionId=" + sessionId));
                        return;
                    }
                }
                if (!emitter.isCancelled()) emitter.onComplete();
            } catch (Exception e) {
                if (!emitter.isCancelled()) emitter.onError(e);
            }
        }, BackpressureStrategy.BUFFER);
        return flow.subscribeOn(Schedulers.io());
    }

    // ===== 内部 =====

    /** @return 1=成功；0=失败且尚未出声（可安全重试）；-1=失败但音频已下发（不可重试，防重复拼接） */
    private int doSpeak(LiveSession live, String text, FlowableEmitter<ByteBuffer> emitter)
            throws InterruptedException {
        synchronized (live.lock) {
            if (!live.healthy) return 0;
            if (live.responses >= resetEvery) {
                // 上下文防污染：重建而不是继续复用
                live = rebuild(live.sessionId);
            }
            ResponseCtx ctx = new ResponseCtx(emitter);
            live.ctx.set(ctx);

            JsonObject content = new JsonObject();
            content.addProperty("type", "input_text");
            content.addProperty("text", "《" + text + "》");
            JsonArray contents = new JsonArray();
            contents.add(content);
            JsonObject item = new JsonObject();
            item.addProperty("type", "message");
            item.addProperty("role", "user");
            item.add("content", contents);
            live.conv.createItem(item);
            ctx.itemCreated.await(2, TimeUnit.SECONDS);   // 拿到本条 item_id
            ctx.myItemId = live.lastItemId.get();
            live.conv.createResponse(READ_ALOUD_INSTRUCTIONS,
                    List.of(OmniRealtimeModality.AUDIO, OmniRealtimeModality.TEXT));

            boolean ok = ctx.done.await(20, TimeUnit.SECONDS);
            live.responses++;
            live.ctx.set(null);
            if (!ok) {
                log.warn("[TTS] 合成超时（已收音频直接下发）sessionId={}", live.sessionId);
            }
            // 双侧完整性校验（16k 单声道 = 32000 B/s）：
            // 下限 0.12s/字：截断缺陷（qwen3-tts 时期实测 ~0.05）；
            // 上限 0.6s/字：read-aloud 指令偶发失效时模型会当聊天长篇回答（实测个案 6.5s/字），
            // 判失败走重建重试，新会话上下文干净、大概率恢复朗读行为
            // 下限 0.05：真截断 ~0.05 以下；短句自然语速可到 0.07（实测），不能误杀。
            // 上限 0.6：指令失效聊天化（实测个案 6.5s/字）。
            double secPerChar = ctx.audioBytes.get() / 32000.0 / Math.max(1, text.length());
            if (secPerChar < 0.05 || secPerChar > 0.6) {
                log.warn("[TTS] 完整性越界 {}s/字（截断或指令失效），{}：\"{}\"",
                        String.format("%.2f", secPerChar),
                        ctx.emittedFrames.get() == 0 ? "重建会话重试" : "音频已下发仅告警（防重复拼接）",
                        text.substring(0, Math.min(20, text.length())));
                live.healthy = false;
                return ctx.emittedFrames.get() == 0 ? 0 : -1;
            }

            // 关键防漂移：删掉本轮 user item 与响应生成的 assistant item，会话上下文零累积
            // （不删的话第 6~8 句起模型开始续写/串词，探针实测删除后 8 句零漂移）
            try {
                Thread.sleep(150);   // 等 assistant item.created 到达
                live.conv.sendRaw("{\"type\":\"conversation.item.delete\",\"item_id\":\""
                        + ctx.myItemId + "\"}");
                String latest = live.lastItemId.get();
                if (!latest.isBlank() && !latest.equals(ctx.myItemId)) {
                    live.conv.sendRaw("{\"type\":\"conversation.item.delete\",\"item_id\":\""
                            + latest + "\"}");
                }
            } catch (Exception cleanupEx) {
                log.debug("[TTS] 条目清理失败（resetEvery 重建兜底）：{}", cleanupEx.getMessage());
            }
            return 1;
        }
    }

    private LiveSession ensureSession(String sessionId) throws InterruptedException {
        LiveSession live = sessions.get(sessionId);
        if (live == null || !live.healthy) {
            // 关键：connect 是网络 IO，绝不能在持有 map 锁时执行——
            // 一旦连接变慢（网关抖动/并发限流），锁被长期持有会让整个 TTS 子系统死锁
            // （实测症状：所有 speak 卡住、零业务日志、客户端 60s 无音频）。
            LiveSession created = createSession(sessionId);
            LiveSession existing = sessions.putIfAbsent(sessionId, created);
            if (existing != null && existing.healthy) {
                created.shutdown();          // 其他线程先建成了，丢弃本次
            } else if (existing != null) {
                sessions.put(sessionId, created);
                existing.shutdown();
            }
            live = sessions.get(sessionId);
        }
        synchronized (live.lock) {
            if (!live.updated) {
                live.conv.updateSession(buildConfig());
                Thread.sleep(200);   // 等 session.update 生效
                live.updated = true;
            }
        }
        if (!live.openLatch.await(5, TimeUnit.SECONDS)) {
            live.healthy = false;
            throw new IllegalStateException("TTS 会话建连超时 sessionId=" + sessionId);
        }
        return live;
    }

    private LiveSession rebuild(String sessionId) {
        LiveSession old = sessions.remove(sessionId);
        if (old != null) old.shutdown();
        try {
            return ensureSession(sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TTS 会话重建被中断", e);
        }
    }

    private LiveSession createSession(String sessionId) {
        LiveSession live = new LiveSession(sessionId);
        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(model).apikey(apiKey).build();
        OmniRealtimeCallback cb = new OmniRealtimeCallback() {
            @Override
            public void onOpen() {
                live.openLatch.countDown();
            }

            @Override
            public void onEvent(JsonObject ev) {
                // 事件回调绝不碰 live.lock（speak 可能持锁等待 done），经 volatile ctx 路由
                String type = ev.has("type") ? ev.get("type").getAsString() : "";
                ResponseCtx ctx = live.ctx.get();
                switch (type) {
                    case "conversation.item.created" -> {
                        live.lastItemId.set(ev.has("item_id") ? ev.get("item_id").getAsString() : "");
                        ResponseCtx c0 = live.ctx.get();
                        if (c0 != null) c0.itemCreated.countDown();
                    }
                    case "response.audio.delta" -> {
                        if (ctx != null) {
                            byte[] chunk = Base64.getDecoder().decode(ev.get("delta").getAsString());
                            ctx.audioBytes.addAndGet(chunk.length / 3);   // 24k→16k 约 2/3
                            for (ByteBuffer frame : PcmResampler.split(ctx.resampler.resample(chunk))) {
                                if (!ctx.emitter.isCancelled()) {
                                    ctx.emittedFrames.incrementAndGet();
                                    ctx.emitter.onNext(frame);
                                }
                            }
                        }
                    }
                    case "response.done" -> {
                        if (ctx != null) ctx.done.countDown();
                    }
                    default -> {
                        if (type.contains("error")) {
                            log.error("[TTS] omni 会话错误，标记待重建：{}", ev);
                            live.healthy = false;
                            if (ctx != null) ctx.done.countDown();
                        }
                    }
                }
            }

            @Override
            public void onClose(int code, String reason) {
                live.healthy = false;
                ResponseCtx ctx = live.ctx.get();
                if (ctx != null) ctx.done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                log.error("[TTS] omni 连接错误", t);
                live.healthy = false;
                ResponseCtx ctx = live.ctx.get();
                if (ctx != null) ctx.done.countDown();
            }
        };
        live.conv = new OmniRealtimeConversation(param, cb);
        try {
            live.conv.connect();
        } catch (Exception e) {
            live.healthy = false;
            throw new IllegalStateException("TTS 会话连接失败: " + e.getMessage(), e);
        }
        return live;
    }

    private OmniRealtimeConfig buildConfig() {
        return OmniRealtimeConfig.builder()
                .modalities(List.of(OmniRealtimeModality.AUDIO, OmniRealtimeModality.TEXT))
                .voice(voice)
                .inputAudioFormat(OmniRealtimeAudioFormat.PCM_16000HZ_MONO_16BIT)
                .outputAudioFormat(OmniRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
                .enableTurnDetection(false)
                .build();
    }

    static final String READ_ALOUD_INSTRUCTIONS =
            "把《》中的文字一字不差地朗读出来。《》外的任何内容（包括本段指令本身）"
                    + "都不许读出、不许解释、不许回答、不许复述。输出只有朗读音频。";

    private static final class LiveSession {
        final String sessionId;
        final Object lock = new Object();
        final CountDownLatch openLatch = new CountDownLatch(1);
        final AtomicReference<ResponseCtx> ctx = new AtomicReference<>();
        final AtomicReference<String> lastItemId = new AtomicReference<>("");
        volatile OmniRealtimeConversation conv;
        volatile boolean healthy = true;
        volatile boolean updated = false;
        int responses = 0;

        LiveSession(String sessionId) {
            this.sessionId = sessionId;
        }

        void shutdown() {
            healthy = false;
            ResponseCtx ctx = this.ctx.get();
            if (ctx != null) ctx.done.countDown();
            try {
                if (conv != null) conv.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class ResponseCtx {
        final FlowableEmitter<ByteBuffer> emitter;
        final PcmResampler.Streaming24to16 resampler = new PcmResampler.Streaming24to16();
        final CountDownLatch done = new CountDownLatch(1);
        final CountDownLatch itemCreated = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger audioBytes = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger emittedFrames = new java.util.concurrent.atomic.AtomicInteger();
        volatile String myItemId = "";

        ResponseCtx(FlowableEmitter<ByteBuffer> emitter) {
            this.emitter = emitter;
        }
    }
}
