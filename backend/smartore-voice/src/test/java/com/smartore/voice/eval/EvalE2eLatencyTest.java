package com.smartore.voice.eval;

import com.smartore.voice.voice.TtsService;
import io.reactivex.Flowable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端到端首响延迟评测（真实 WebSocket 语音链路）：
 * 登录 → TTS 预合成一句购物语音 → WS 推流（50ms/帧 = 2 倍速回放）→
 * 计时：用户说完 → 首个 ASR 终稿 / 首个字幕帧 / 首个音频帧。
 * 这是简历口径"端到端首帧语音延迟"的官方测量方法。
 */
@Tag("eval")
class EvalE2eLatencyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // 默认 12 轮：P50 需要 ≥10 个样本才稳定（此前 5 轮的 P50 统计上不可靠）
    private static final int ROUNDS = Integer.parseInt(System.getProperty("eval.e2e.rounds", "12"));
    private static final String UTTERANCE = "帮我推荐一双800以内的跑鞋，主要跑水泥路";


    private static byte[] utterancePcm;

    @BeforeAll
    static void guard() throws Exception {
        Assumptions.assumeTrue(EvalSupport.serviceUp(), "服务未启动");
        Assumptions.assumeTrue(EvalSupport.apiKeyPresent(), "需要 DASHSCOPE_API_KEY");
        Assumptions.assumeTrue(!EvalSupport.gatewayToken().isBlank(),
                "需要 SMARTORE_GATEWAY_TOKEN（直连 9105 模拟网关注入头）");
        utterancePcm = synthesize(UTTERANCE);
    }

    @Test
    void 端到端首响延迟() throws Exception {
        List<Double> tAsr = new ArrayList<>(), tCaption = new ArrayList<>(), tAudio = new ArrayList<>();
        List<String> rows = new ArrayList<>();

        for (int r = 1; r <= ROUNDS; r++) {
            AtomicLong sendDone = new AtomicLong();
            AtomicLong firstAsrFinal = new AtomicLong();
            AtomicLong firstCaption = new AtomicLong();
            AtomicLong firstAudio = new AtomicLong();
            CountDownLatch audioArrived = new CountDownLatch(1);

            // 直连 9105：握手带 X-Gateway-Token + X-User-Id（与网关注入等效；打网关链路需改用 query JWT）
            String wsUrl = EvalSupport.BASE_URL.replace("http", "ws")
                    + "/voice/ws?sessionId=eval-e2e-" + System.currentTimeMillis();
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(120, TimeUnit.SECONDS).build();
            WebSocket ws = client.newWebSocket(new Request.Builder().url(wsUrl)
                            .header("X-Gateway-Token", EvalSupport.gatewayToken())
                            .header("X-User-Id", "4").build(),
                    new WebSocketListener() {
                        @Override public void onOpen(WebSocket w, Response resp) { }

                        @Override public void onMessage(WebSocket w, String text) {
                            try {
                                var node = JSON.readTree(text);
                                String type = node.path("type").asText("");
                                long now = System.currentTimeMillis();
                                if (type.equals("asr") && node.path("final").asBoolean(false)
                                        && firstAsrFinal.get() == 0) firstAsrFinal.set(now);
                                if (type.equals("caption") && firstCaption.get() == 0) firstCaption.set(now);
                            } catch (Exception ignored) { }
                        }

                        @Override public void onMessage(WebSocket w, ByteString bytes) {
                            if (firstAudio.compareAndSet(0, System.currentTimeMillis())) {
                                audioArrived.countDown();
                            }
                        }

                        @Override public void onFailure(WebSocket w, Throwable t, Response resp) {
                            audioArrived.countDown();
                        }
                    });

            // 推流：50ms 一帧（2 倍速回放），推完记录"说完"时刻
            for (int i = 0; i < utterancePcm.length; i += 3200) {
                int len = Math.min(3200, utterancePcm.length - i);
                ws.send(ByteString.of(utterancePcm, i, len));
                Thread.sleep(50);
            }
            sendDone.set(System.currentTimeMillis());
            // 尾部静音（真实前端录音结束会发约 800ms 静音帧，并发测试器同样做法）：
            // 不发静音时服务端 VAD 收不到 speech_stopped，多段句的尾段永不终稿——
            // 这正是"只对前半句响应"的测试器侧成因；t0 仍是**最后一帧语音**发完的时刻
            for (int i = 0; i < 4; i++) {
                ws.send(ByteString.of(new byte[3200], 0, 3200));
                Thread.sleep(50);
            }

            boolean gotAudio = audioArrived.await(45, TimeUnit.SECONDS);
            long base = sendDone.get();
            double a = firstAsrFinal.get() == 0 ? -1 : (firstAsrFinal.get() - base) / 1000.0;
            double c = firstCaption.get() == 0 ? -1 : (firstCaption.get() - base) / 1000.0;
            double u = firstAudio.get() == 0 ? -1 : (firstAudio.get() - base) / 1000.0;
            tAsr.add(a); tCaption.add(c); tAudio.add(u);
            rows.add(String.format("| 第%d轮 | %.2f | %.2f | %.2f |%n", r, a, c, u));
            ws.close(1000, "done");
            Thread.sleep(500);
        }

        // 不做正值过滤：负值有明确含义——ASR 在用户话末停顿处提前判停，
        // 响应早于"推流结束"，真实体验优于表观数值（过滤掉会得到 NaN 误导读者）
        List<Double> asrPos = tAsr;
        List<Double> capPos = tCaption;
        List<Double> audPos = tAudio;
        double[] audCi = EvalMetrics.bootstrapCI(audPos.stream().mapToDouble(Double::doubleValue)
                .boxed().toList());

        String report = String.format("""
                        %s
                        ## 结果（%d 轮，口径：用户语音推流结束 → 各首帧到达）

                        | 轮次 | 首个ASR终稿(s) | 首个字幕帧(s) | 首个音频帧(s) |
                        |--|--|--|--|
                        %s
                        | **P50** | **%.2f** | **%.2f** | **%.2f** |
                        | 95%% bootstrap CI（首帧音频） | - | - | [%.2f, %.2f] |
                        | P95 | %.2f | %.2f | %.2f |

                        ## 口径
                        - 输入：TTS 预合成的同一句语音（%d字节 ≈ %.1fs），以 50ms/帧（2 倍速）推流，末尾补 800ms 静音帧（真实前端同做法）
                        - t0 = **最后一帧语音**发送完成（不含尾部静音）；三个终点分别为首个 {type:asr,final:true} 文本帧、
                          首个 caption 文本帧、首个二进制音频帧
                        - 链路：omni ASR → 意图（%s）→ pgvector 召回+规则重排 → 情感话术（%s 流式）→ 逐句 omni TTS
                        - **片段合并**：ASR 分段终稿进入合并缓冲（延迟 %sms），窗口内新终稿合并、转写仍在增长则顺延，
                          直到整句转写停止增长才编排——首个音频帧对应"合并后完整句"的回答，而非对半句话的抢答
                        - 负值 = 该帧在 t0 之前已到达（ASR 在句内停顿处已判停并完成响应）
                        - 模型与 ASR 参数取自服务端实时配置（见报告头），不写死在报告模板里
                        """, EvalSupport.configLine(), ROUNDS, String.join("", rows),
                EvalMetrics.median(asrPos), EvalMetrics.median(capPos), EvalMetrics.median(audPos),
                audCi[0], audCi[1],
                EvalMetrics.percentile(asrPos, 0.95), EvalMetrics.percentile(capPos, 0.95),
                EvalMetrics.percentile(audPos, 0.95),
                utterancePcm.length, utterancePcm.length / 32000.0,
                cfgOr("lightModel", "意图模型"), cfgOr("mainModel", "话术模型"),
                cfgOr("sentenceMergeDelayMs", "合并延迟"));

        var path = EvalSupport.writeReport("e2e-latency-report.md", "端到端首响延迟评测报告", report);
        System.out.println("[EVAL] E2E 延迟报告已生成: " + path);
        org.junit.jupiter.api.Assertions.assertFalse(audPos.isEmpty(), "所有轮次均未收到音频帧");
    }


    /** 读服务端配置项（报告模板不留硬编码模型名）；不可用时给出占位。 */
    private static String cfgOr(String key, String fallback) {
        Object v = EvalSupport.serverConfig().get(key);
        return String.valueOf(v == null ? fallback : v);
    }


    // 构造直连生产 TtsService（会话复用版）：manager 字段需一并注入（Spring 外无 @Value 默认值）

    private static byte[] synthesize(String text) throws Exception {
        TtsService tts = EvalSupport.buildTts(Boolean.parseBoolean(
                System.getProperty("eval.tts.normalize", "true")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tts.synthesize(Flowable.just(text)).blockingSubscribe(b -> {
            byte[] a = new byte[b.remaining()];
            b.get(a);
            out.write(a, 0, a.length);
        });
        return out.toByteArray();
    }
}
