package com.smartore.voice.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测器公共支撑：HTTP 调用、JSONL 黄金集加载、Markdown 报告生成。
 *
 * <p>Smartore 版鉴权模型（与原 jc 的 Sa-Token 登录不同）：
 * <ul>
 *   <li>HTTP 调试端点：直连服务 9105，头 {@code X-Internal-Token}（集群内凭证，
 *       从 run/runtime.env 读取，eval 运行前 source 或 -Dsmartore.internal.token）。</li>
 *   <li>WS 语音链路：默认也直连 9105 的 /voice/ws，握手带 {@code X-Gateway-Token} +
 *       {@code X-User-Id} 头（模拟网关注入）；要打真实网关链路时 -Deval.base.url 指向
 *       9080 并改用 query token。</li>
 * </ul>
 * 注意：independent-intent-200 / v2-speech-40 等封存数据集与原项目商品目录（跑鞋/手表/耳机/口红）
 * 绑定，迁移到 Smartore 类目后需重新校准（见 eval/README.md）。
 */
public final class EvalSupport {

    /** 直连服务；要打网关链路时 -Deval.base.url=http://localhost:9080 */
    public static final String BASE_URL =
            System.getProperty("eval.base.url", "http://localhost:9105");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final com.fasterxml.jackson.databind.json.JsonMapper JSON =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().build();

    private EvalSupport() {
    }

    /** 生产默认 ASR 参数（与 application.yml 缺省值同源）。 */
    public static int prodVadSilenceMs() {
        return Integer.getInteger("eval.asr.vad-ms", 400);
    }

    public static int prodFlushGraceMs() {
        return Integer.getInteger("eval.asr.grace-ms", 400);
    }

    public static int prodSettleMs() {
        return Integer.getInteger("eval.asr.settle-ms", 700);
    }

    /** 集群内凭证：HTTP 调试端点的鉴权头值。 */
    public static String internalToken() {
        String t = System.getProperty("smartore.internal.token",
                System.getenv("SMARTORE_INTERNAL_TOKEN"));
        return t == null ? "" : t;
    }

    /** 网关凭证：WS 直连握手时模拟网关注入（X-Gateway-Token + X-User-Id）。 */
    public static String gatewayToken() {
        String t = System.getProperty("smartore.gateway.token",
                System.getenv("SMARTORE_GATEWAY_TOKEN"));
        return t == null ? "" : t;
    }

    /** 语音/embedding 用的 DashScope key（Smartore 底座里即 SMARTORE_EMBED_API_KEY）。 */
    public static String dashscopeKey() {
        String k = System.getenv("DASHSCOPE_API_KEY");
        if (k == null || k.isBlank()) k = System.getenv("SMARTORE_EMBED_API_KEY");
        return k == null ? "" : k;
    }

    public static boolean serviceUp() {
        try {
            HttpResponse<String> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("UP");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean apiKeyPresent() {
        String k = dashscopeKey();
        return !k.isBlank() && !k.startsWith("sk-xxx");
    }

    public static String postJson(String url, String jsonBody) throws IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", internalToken());
        return HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    public static String postText(String url, String rawBody) throws IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "text/plain;charset=UTF-8")
                .header("X-Internal-Token", internalToken());
        return HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    /** 加载 JSONL 黄金集（一行一 JSON 对象，键为 String 值为 Object）。 */
    public static List<Map<String, Object>> loadGolden(String path) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            rows.add(JSON.readValue(line, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        }
        return rows;
    }

    /** 服务端当前生效配置（模型/ASR 参数）；不可用时返回空表（报告降级为"未知"）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> serverConfig() {
        try {
            String body = postJson(BASE_URL + "/voice/debug/config/config", "{}");
            return JSON.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 报告头部配置行：从服务端取被测模型与关键参数（报告与被测对象不脱节）。 */
    public static String configLine() {
        Map<String, Object> c = serverConfig();
        if (c.isEmpty()) return "> 配置：不可用（/voice/debug/config/config 未响应，服务未起或 debug 闸门关闭）\n";
        return String.format("> 配置：意图=%s｜话术=%s｜thinking=%s｜ASR=%s(vad=%s/grace=%s/settle=%s)｜TTS=%s｜合并延迟=%sms%n",
                c.get("lightModel"), c.get("mainModel"), c.get("enableThinking"),
                c.get("asrModel"), c.get("vadSilenceMs"), c.get("flushGraceMs"), c.get("settleMs"),
                c.get("ttsModel"), c.get("sentenceMergeDelayMs"));
    }

    /** 真正的逐块间隔灌流（delay 是整体平移不是间隔！zipWith interval 才是真实语速口径）。 */
    public static io.reactivex.Flowable<java.nio.ByteBuffer> paced(
            List<java.nio.ByteBuffer> chunks, long msPerChunk) {
        return io.reactivex.Flowable.fromIterable(chunks)
                .zipWith(io.reactivex.Flowable.interval(msPerChunk, java.util.concurrent.TimeUnit.MILLISECONDS),
                        (b, t) -> b);
    }

    /** 生成 Markdown 报告文件，返回报告路径。 */
    public static Path writeReport(String name, String title, String body) throws IOException {
        Path dir = Path.of("eval/reports");
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "# " + title + "\n\n> 生成时间：" + LocalDateTime.now()
                + "｜环境：" + BASE_URL + "\n\n" + body, StandardCharsets.UTF_8);
        return file;
    }

    // ===== 评测器公共设施 =====

    /** 反射注入 @Value 字段：语音类评测器脱离 Spring 构造生产服务时必须显式注入生产参数。 */
    public static <T> T reflectSet(T service, Map<String, Object> fields) {
        try {
            for (var e : fields.entrySet()) {
                java.lang.reflect.Field f = service.getClass().getDeclaredField(e.getKey());
                f.setAccessible(true);
                f.set(service, e.getValue());
            }
            return service;
        } catch (Exception e) {
            throw new IllegalStateException("反射注入失败: " + e.getMessage(), e);
        }
    }

    /** 直连生产 TtsService（会话复用版）：manager 字段需一并注入（Spring 外无 @Value 默认值）。 */
    public static com.smartore.voice.voice.TtsService buildTts(boolean normalizeNumbers) {
        com.smartore.voice.voice.OmniTtsSessionManager mgr =
                new com.smartore.voice.voice.OmniTtsSessionManager();
        reflectSet(mgr, Map.of(
                "apiKey", dashscopeKey(),
                "model", System.getProperty("eval.tts.model", "qwen3.5-omni-flash-realtime"),
                "voice", "Tina",
                "resetEvery", 8,
                "sessionMax", 200));
        com.smartore.voice.voice.TtsService tts =
                new com.smartore.voice.voice.TtsService(mgr);
        return reflectSet(tts, Map.of("normalizeNumbers", normalizeNumbers));
    }

    /** 直连生产 TtsService（默认开启数字归一化，即生产默认）。 */
    public static com.smartore.voice.voice.TtsService buildTts() {
        return buildTts(true);
    }

    /** 直连生产 AsrService：注入与生产同源的 VAD 参数。 */
    public static com.smartore.voice.voice.AsrService buildAsr() {
        return reflectSet(new com.smartore.voice.voice.AsrService(), Map.of(
                "apiKey", dashscopeKey(),
                "model", System.getProperty("eval.asr.model", "qwen3.5-omni-flash-realtime"),
                "vadSilenceMs", prodVadSilenceMs(),
                "flushGraceMs", prodFlushGraceMs(),
                "settleMs", prodSettleMs()));
    }

    /** TTS 预合成整句为 PCM（评测输入音频统一来源）。 */
    public static byte[] synthesize(String text) throws Exception {
        com.smartore.voice.voice.TtsService tts = buildTts(true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        tts.synthesize(io.reactivex.Flowable.just(text)).blockingSubscribe(b -> {
            byte[] a = new byte[b.remaining()];
            b.get(a);
            out.write(a, 0, a.length);
        });
        return out.toByteArray();
    }

    /** PCM → 3200 字节帧（100ms @16k/16bit/mono）。 */
    public static List<java.nio.ByteBuffer> pcmChunks(byte[] pcm) {
        List<java.nio.ByteBuffer> chunks = new ArrayList<>();
        for (int i = 0; i < pcm.length; i += 3200) {
            chunks.add(java.nio.ByteBuffer.wrap(
                    java.util.Arrays.copyOfRange(pcm, i, Math.min(i + 3200, pcm.length))));
        }
        return chunks;
    }

    private static volatile Map<Long, Double> pricesCache;

    /**
     * 当前同步目录的 id→价格表：从运行中服务的 /voice/debug/catalog-prices 懒加载，
     * 替代原项目硬编码的 30 件种子价目表（评测基准与生产数据同源，不再脱节）。
     */
    @SuppressWarnings("unchecked")
    public static Map<Long, Double> prices() {
        if (pricesCache == null) {
            synchronized (EvalSupport.class) {
                if (pricesCache == null) {
                    try {
                        HttpResponse<String> resp = HTTP.send(
                                HttpRequest.newBuilder(URI.create(BASE_URL + "/voice/debug/catalog-prices"))
                                        .header("X-Internal-Token", internalToken()).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        String body = resp.body();
                        Map<String, Object> wrapped = JSON.readValue(body,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        Map<String, Object> data = (Map<String, Object>) wrapped.get("data");
                        Map<Long, Double> out = new java.util.HashMap<>();
                        if (data != null) {
                            data.forEach((k, v) -> out.put(Long.parseLong(k), ((Number) v).doubleValue()));
                        }
                        pricesCache = Map.copyOf(out);
                    } catch (Exception e) {
                        throw new IllegalStateException("目录价格加载失败（服务未起？）: " + e.getMessage(), e);
                    }
                }
            }
        }
        return pricesCache;
    }

    /** 文本向量化（DashScope embedding；模型与生产 voice-shopping.embedding.model 同源）。 */
    public static float[] embed(String text) throws Exception {
        String model = System.getProperty("eval.embed.model",
                System.getenv().getOrDefault("SMARTORE_EMBED_MODEL", "text-embedding-v4"));
        String body = "{\"model\":\"" + model + "\",\"input\":{\"texts\":["
                + JSON.writeValueAsString(text) + "]}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"))
                .header("Authorization", "Bearer " + dashscopeKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        var node = JSON.readTree(resp.body()).path("output").path("embeddings").get(0).path("embedding");
        float[] v = new float[node.size()];
        for (int i = 0; i < node.size(); i++) v[i] = (float) node.get(i).asDouble();
        return v;
    }
}
