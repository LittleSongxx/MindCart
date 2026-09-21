package com.smartore.voice.voice;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 语音合成：委托 OmniTtsSessionManager 的常驻会话（复用+预热+自动重建）。
 * 文本前处理（金额数字→中文读法）在这里做；会话生命周期管理全部在 manager。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TtsService {

    private final OmniTtsSessionManager sessions;

    /** 数字归一化开关：默认开（消融评测用 false 对照）。 */
    @Value("${voice-shopping.tts.normalize-numbers:true}")
    private boolean normalizeNumbers;

    /**
     * 一次流式合成（系统会话，供启动预热等无会话上下文的调用方）。
     *
     * @param textStream LLM 生成的文字流（上游句子聚合器发来的是单个完整句）
     * @return 音频帧流（PCM 16k 16bit）
     */
    public Flowable<ByteBuffer> synthesize(Flowable<String> textStream) {
        return synthesize(OmniTtsSessionManager.SYSTEM_SESSION, textStream);
    }

    /**
     * 一次流式合成（复用该会话的常驻 omni 连接）。轮次开始先调 prewarm(sessionId)。
     */
    public Flowable<ByteBuffer> synthesize(String sessionId, Flowable<String> textStream) {
        Flowable<ByteBuffer> flow = Flowable.create(emitter -> {
            // 句子聚合器保证一次只发一个完整句，这里把流收拢成整句再交给会话朗读
            StringBuilder text = new StringBuilder();
            CountDownLatch textDone = new CountDownLatch(1);
            textStream.subscribe(text::append, emitter::onError, textDone::countDown);
            if (!textDone.await(15, TimeUnit.SECONDS)) {
                emitter.onError(new IllegalStateException("等待合成文本超时"));
                return;
            }
            // omni 会把 "800" 逐位念成"八零零"，价格语境的数字先转中文读法
            String raw = text.toString();
            String speakText = normalizeNumbers ? TtsTextNormalizer.normalize(raw) : raw;
            sessions.speak(sessionId, speakText)
                    .subscribe(emitter::onNext, emitter::onError, emitter::onComplete);
        }, BackpressureStrategy.BUFFER);
        return flow.subscribeOn(Schedulers.io());
    }

    /** 轮次开始时预热：与意图/检索/话术并行建连，把 ~0.9s 会话握手移出关键路径。 */
    public void prewarm(String sessionId) {
        sessions.prewarm(sessionId);
    }

    /** 会话结束（WS 断开）时释放常驻 TTS 会话。 */
    public void close(String sessionId) {
        sessions.close(sessionId);
    }
}
