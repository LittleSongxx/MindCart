package com.mindcart.voice.eval;

import com.mindcart.voice.voice.TtsService;
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
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发压测：N 个 WebSocket 会话同时跑完整语音轮（复用 E2E 的 TTS→WS→首帧音频链路），
 * 统计成功率/首响分布/错误类型——验证会话池与线程池在多用户下的容量。
 */
@Tag("eval")
class EvalConcurrencyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int N = Integer.parseInt(System.getProperty("eval.conc.n", "8"));

    private static byte[] utterancePcm;

    @BeforeAll
    static void guard() throws Exception {
        Assumptions.assumeTrue(EvalSupport.serviceUp() && EvalSupport.apiKeyPresent());
        Assumptions.assumeTrue(!EvalSupport.gatewayToken().isBlank(), "需要 MINDCART_GATEWAY_TOKEN");
        utterancePcm = synthesize("帮我推荐一双800以内的跑鞋");
        System.out.printf("[EVAL] TTS 预合成: %d bytes (%.1fs)%n", utterancePcm.length, utterancePcm.length / 32000.0);
        Assumptions.assumeTrue(utterancePcm.length > 16000, "TTS 预合成失败（音频 <0.5s）");
    }

    @Test
    void 并发压测() throws Exception {
        List<Future<double[]>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(N);
        AtomicInteger errors = new AtomicInteger();
        List<String> errorTypes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < N; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    CountDownLatch audioArrived = new CountDownLatch(1);
                    long[] firstAudioAt = {0};
                    OkHttpClient client = new OkHttpClient.Builder()
                            .readTimeout(120, TimeUnit.SECONDS).build();
                    String wsUrl = EvalSupport.BASE_URL.replace("http", "ws")
                            + "/voice/ws?sessionId=eval-conc-" + System.currentTimeMillis() + "-" + idx;
                    WebSocket ws = client.newWebSocket(new Request.Builder().url(wsUrl)
                                    .header("X-Gateway-Token", EvalSupport.gatewayToken())
                                    .header("X-User-Id", "4").build(),
                            new WebSocketListener() {
                                @Override public void onMessage(WebSocket w, ByteString bytes) {
                                    if (firstAudioAt[0] == 0) {
                                        firstAudioAt[0] = System.currentTimeMillis();
                                        audioArrived.countDown();
                                    }
                                }
                                @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                                    errorTypes.add("WS失败:" + t.getClass().getSimpleName());
                                    audioArrived.countDown();
                                }
                            });
                    for (int j = 0; j < utterancePcm.length; j += 3200) {
                        ws.send(ByteString.of(utterancePcm, j, Math.min(3200, utterancePcm.length - j)));
                        Thread.sleep(50);
                    }
                    // 垫 2s 静音帧：ASR 的服务端 VAD 需要持续音频流才能判停
                    // （真实客户端录音期间持续推流；推流即停会让 VAD 收不到"静音"而永不定稿）
                    byte[] silence = new byte[3200];
                    for (int j = 0; j < 20; j++) {
                        ws.send(ByteString.of(silence));
                        Thread.sleep(100);
                    }
                    long sentAt = System.currentTimeMillis();
                    boolean got = audioArrived.await(60, TimeUnit.SECONDS);
                    ws.close(1000, "done");
                    if (!got) {
                        errors.incrementAndGet();
                        errorTypes.add("60s超时无音频");
                        return new double[]{-1, -1};
                    }
                    return new double[]{1, (firstAudioAt[0] - sentAt) / 1000.0};
                } catch (Exception e) {
                    errors.incrementAndGet();
                    errorTypes.add("异常:" + e.getClass().getSimpleName());
                    return new double[]{-1, -1};
                }
            }));
        }
        List<Double> latencies = new ArrayList<>();
        int success = 0;
        for (Future<double[]> f : futures) {
            double[] r = f.get(120, TimeUnit.SECONDS);
            if (r[0] > 0) {
                success++;
                latencies.add(r[1]);
            }
        }
        pool.shutdown();

        String report = String.format(
                "## 并发压测（N=%d 同时语音会话）\n\n"
                        + "| 指标 | 值 |\n|--|--|\n"
                        + "| 成功率 | **%d/%d（%.0f%%）** |\n"
                        + "| 首帧音频延迟 P50 | %.2fs |\n"
                        + "| 首帧音频延迟 P95（max） | %.2fs |\n"
                        + "| 错误类型 | %s |\n\n"
                        + "首帧延迟含分段合并延迟（voice-shopping.orch.sentence-merge-delay-ms，默认 500ms）：\n"
                        + "首帧对应的是\"合并后完整句\"的回答。关掉合并延迟可换回更低的表观首帧，但会重新出现\"对半句话抢答\"。\n\n"
                        + """
                        ## 历史根因（保留记录，勿删——曾因报告重写丢失过一次）

                        初版压测 0/8 全部超时，经线程级日志 + 对照实验确认**不是 WebSocket 层容量问题**，而是三个缺陷叠加：

                        | # | 缺陷 | 影响面 | 修复 |
                        |--|--|--|--|
                        | 1 | `session_state.slots` NOT NULL 约束违例（Hibernate 插入显式 null 覆盖 DB `DEFAULT '{}'`） | **生产真 bug**：所有非推荐意图（闲聊/下单/越界）的新会话首次交互必失败，SQL 异常中断流式链路→客户端无音频 | 实体字段默认空 Map + load() 显式初始化 + 回归单测 |
                        | 2 | WS 容器默认文本缓冲 8KB | 生产隐患：商品卡片/长话术等大 JSON 下行触发 1009 静默断连 | `ServletServerContainerFactoryBean` 1MB + Tomcat `context-parameters` 512KB 双保险 |
                        | 3 | 测试器推流即停，无尾部静音帧 | 测试器缺陷：服务端 VAD 需要持续音频流才能判停（真实前端录音结束会发 800ms 静音帧） | 测试器垫 2s 静音帧（E2E 测试器同此做法） |

                        诊断判据：线程级日志（`[Orc]`/`[Stream]`/TTS 告警全无 → 卡在更早的保存步骤）、
                        `LoggingWebSocketHandlerDecorator` 的 1009 关闭码、Java 最小 WS 客户端垫/不垫静音的对照实验。
                        """,
                N, success, N, success * 100.0 / N,
                EvalMetrics.median(latencies), EvalMetrics.percentile(latencies, 0.95),
                errorTypes.isEmpty() ? "无" : String.join("; ", errorTypes.stream().distinct().toList()));

        var path = EvalSupport.writeReport("concurrency-report.md", "并发压测报告", report);
        System.out.printf("[EVAL] 并发 %d/%d 成功，P50=%.2fs P95=%.2fs，报告: %s%n",
                success, N, EvalMetrics.median(latencies), EvalMetrics.percentile(latencies, 0.95), path);
    }

    private static byte[] synthesize(String text) throws Exception {
        var mgr = new com.mindcart.voice.voice.OmniTtsSessionManager();
        EvalSupport.reflectSet(mgr, Map.of("apiKey", System.getenv("DASHSCOPE_API_KEY"),
                "model", "qwen3.5-omni-flash-realtime", "voice", "Tina",
                "resetEvery", 8, "sessionMax", 200));
        TtsService tts = new TtsService(mgr);
        EvalSupport.reflectSet(tts, Map.of("normalizeNumbers", Boolean.TRUE));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tts.synthesize(Flowable.just(text)).blockingSubscribe(b -> {
            byte[] a = new byte[b.remaining()];
            b.get(a);
            out.write(a, 0, a.length);
        });
        return out.toByteArray();
    }
}
