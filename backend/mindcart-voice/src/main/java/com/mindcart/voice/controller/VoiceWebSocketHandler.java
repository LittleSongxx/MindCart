package com.mindcart.voice.controller;

import com.mindcart.voice.agent.AgentFactory;
import com.mindcart.voice.dto.EmotionResult;
import com.mindcart.voice.entity.StreamChunk;
import com.mindcart.voice.service.LongTermMemoryWriter;
import com.mindcart.voice.service.OrchestratorService;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcart.voice.voice.AsrService;
import com.mindcart.voice.voice.TtsService;
import com.mindcart.voice.voice.UtteranceMerger;
import io.reactivex.Flowable;
import io.reactivex.processors.PublishProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final AsrService asr;
    private final TtsService tts;
    private final ObjectMapper mapper;
    private final OrchestratorService orchestratorService;
    private final LongTermMemoryWriter longTermMemoryWriter;
    private final AgentFactory agentFactory;
    private final com.mindcart.voice.service.SessionService sessionService;
    private final com.mindcart.voice.service.ConversationJournal journal;
    private final com.mindcart.voice.integration.ProfileOrderSync profileOrderSync;

    // 每个 WebSocket session 有自己的音频流
    private final Map<String, PublishProcessor<ByteBuffer>> audioStreams = new ConcurrentHashMap<>();

    /** 每个 session 的分段终稿合并器（见 UtteranceMerger：避免长句在逗号停顿被切碎成多轮编排）。 */
    private final Map<String, UtteranceMerger> mergers = new ConcurrentHashMap<>();

    /** 合并调度器：合并延迟取 voice-shopping.orch.sentence-merge-delay-ms（0=退化为逐段立即触发）。 */
    private final java.util.concurrent.ScheduledExecutorService mergerScheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "utt-merge");
                t.setDaemon(true);
                return t;
            });

    @org.springframework.beans.factory.annotation.Value(
            "${voice-shopping.orch.sentence-merge-delay-ms:500}")
    private long sentenceMergeDelayMs;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        PublishProcessor<ByteBuffer> audioStream = PublishProcessor.create();
        audioStreams.put(session.getId(), audioStream);

        // 建会话（渠道归因）→ 补画像 → 冷启动回灌上下文：都在编排前完成，失败互不影响
        String sessionId = (String) session.getAttributes().get("sessionId");
        Long userId = (Long) session.getAttributes().get("userId");
        if (sessionId != null && userId != null) {
            try {
                sessionService.openIfAbsent(sessionId, userId,
                        (String) session.getAttributes().get("channel"),
                        (Long) session.getAttributes().get("boundProductId"));
                profileOrderSync.syncRecentOrders(userId);
                journal.hydrateIfCold(sessionId);
            } catch (Exception e) {
                log.warn("会话初始化降级（不影响对话）: {}", e.getMessage());
            }
        }

        asr.recognize(audioStream, (text, isEnd) -> {
            // session 可能已经被前端关了（ASR 服务端的尾部结果回来晚于 close），直接跳过
            if (!session.isOpen()) return;
            try {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(
                        Map.of("type", "asr", "text", text, "final", isEnd))));
                if (isEnd) {
                    // 分段终稿进合并缓冲，延迟窗口内的新终稿/继续说话会被合并/撤销，
                    // 只对"这轮话说完了"的完整文本触发一次编排
                    mergers.computeIfAbsent(session.getId(),
                            id -> new UtteranceMerger(mergerScheduler, sentenceMergeDelayMs,
                                    merged -> onUserUtterance(session, merged)))
                            .onFinal(text);
                } else {
                    UtteranceMerger m = mergers.get(session.getId());
                    if (m != null) m.onPartial(text);
                }
            } catch (Exception e) {
                log.warn("下发 ASR 结果失败（session 可能已关闭）: {}", e.getMessage());
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        PublishProcessor<ByteBuffer> stream = audioStreams.get(session.getId());
        if (stream != null) stream.onNext(message.getPayload());
    }

    /**
     * 上行 JSON 控制帧。目前只有一种：order_result——前端代执行下单/取消的结果回传，
     * 交给编排器口播收口（与正常回复共用文本 + TTS 通道）。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = (String) session.getAttributes().get("sessionId");
        try {
            Map<String, Object> frame = mapper.readValue(message.getPayload(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (!"order_result".equals(frame.get("type"))) return;
            String requestId = (String) frame.get("requestId");
            boolean success = Boolean.TRUE.equals(frame.get("success"));
            String orderNo = (String) frame.get("orderNo");
            String error = (String) frame.get("error");
            orchestratorExecutor.submit(() -> {
                try {
                    orchestratorService.streamOrderResult(sessionId, requestId, success, orderNo, error)
                            .subscribe(chunk -> {
                                if (!session.isOpen()) return;
                                try {
                                    switch (chunk.type()) {
                                        case TEXT -> sendJson(session,
                                                Map.of("type", "caption", "text", chunk.text()));
                                        case AUDIO -> session.sendMessage(new BinaryMessage(chunk.audio()));
                                        default -> { }
                                    }
                                } catch (IOException e) {
                                    log.warn("order_result 回执发送失败: {}", e.getMessage());
                                }
                            }, err -> log.error("order_result 处理失败 sessionId={}", sessionId, err));
                } catch (Exception e) {
                    log.error("order_result 处理错误 sessionId={}", sessionId, e);
                }
            });
        } catch (Exception e) {
            log.warn("无法解析的上行文本帧（忽略）: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PublishProcessor<ByteBuffer> stream = audioStreams.remove(session.getId());
        if (stream != null) stream.onComplete();
        UtteranceMerger merger = mergers.remove(session.getId());
        if (merger != null) merger.close();   // 撤销待触发的合并任务
        String sessionId = (String) session.getAttributes().get("sessionId");
        Long userId = (Long) session.getAttributes().get("userId");
        if (sessionId != null && userId != null) {
            longTermMemoryWriter.flushOnSessionEnd(sessionId, userId);
            agentFactory.remove(sessionId);
            tts.close(sessionId);   // 释放常驻 TTS omni 会话
            sessionService.close(sessionId);   // 会话收口：ended_at + outcome 归因（ORDERED/ABANDONED）
        }
    }

    /** 编排执行线程池：ASR 回调运行在 SDK 的 OkHttp 线程上，同步跑完整编排
     *  （LLM 数秒 + TTS 等待）会阻塞 SDK 的后续事件处理（含 TTS 事件，形成互等）。 */
    private final java.util.concurrent.ExecutorService orchestratorExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "voice-orch");
                t.setDaemon(true);
                return t;
            });

    private void onUserUtterance(WebSocketSession session, String utterance) {
        if (utterance == null || utterance.isBlank()) return;
        // 立即从 ASR 回调线程切走：回调线程只做事件分发，重活交给独立线程池
        orchestratorExecutor.submit(() -> doOrchestrate(session, utterance));
    }

    private void doOrchestrate(WebSocketSession session, String utterance) {
        String sessionId = (String) session.getAttributes().get("sessionId");
        Long userId = (Long) session.getAttributes().get("userId");

        try {
            Flux<StreamChunk> chunks = orchestratorService.streamHandle(sessionId, userId, utterance);

            chunks.subscribe(chunk -> {
                if (!session.isOpen()) return;            // session 关掉之后不再写
                try {
                    switch (chunk.type()) {
                        case TEXT -> sendJson(session,
                                Map.of("type", "caption", "text", chunk.text()));
                        case AUDIO -> session.sendMessage(new BinaryMessage(chunk.audio()));
                        case PRODUCTS -> sendJson(session,
                                Map.of("type", "recommendation", "items", chunk.products()));
                        case ACTION -> sendJson(session, chunk.products());
                    }
                } catch (IOException e) {
                    log.warn("WebSocket 发送失败（session 可能已关闭）: {}", e.getMessage());
                }
            }, err -> log.error("流式失败 sessionId={}", sessionId, err));
        } catch (Exception e) {
            log.error("流式处理错误 sessionId={}", sessionId, e);
        }
    }

    /**
     * 序列化任意对象后通过 WebSocket 文本帧发给前端
     */
    private void sendJson(WebSocketSession session, Object payload) throws IOException {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
    }
}