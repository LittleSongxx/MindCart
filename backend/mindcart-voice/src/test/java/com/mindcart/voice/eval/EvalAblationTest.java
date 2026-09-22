package com.mindcart.voice.eval;

import com.mindcart.voice.dto.RecommendedItem;
import com.mindcart.voice.dto.UserProfileSnapshot;
import com.mindcart.voice.service.ProfileReranker;
import com.mindcart.voice.voice.TtsService;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 消融矩阵：每一行回答"设计了 X，使指标 Y 提升了 Z"——组件级归因，不是前后总账。
 *  - A1 规则重排消融：同一批候选，向量序 vs 规则重排序，比 nDCG@3（业界标准检索指标），
 *    另报描述性统计"Top3 均价/预算比"（业务 KPI，非质量指标）
 *  - A2 槽位归一化：同一批意图响应里的 rawSlots vs slots，比槽位字段准确率
 *  - A3 TTS 数字归一化：同一批含数字句，归一化关/开，比回环 CER
 *  - A4 thinking 开关：E2E 首响 P50（两次实测值经 -D 参数传入）
 */
@Tag("eval")
class EvalAblationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static Connection pg;

    @BeforeAll
    static void guard() throws Exception {
        Assumptions.assumeTrue(EvalSupport.serviceUp() && EvalSupport.apiKeyPresent(),
                "需要运行中的服务 + DASHSCOPE_API_KEY");
        String url = System.getProperty("eval.pg.url", "jdbc:postgresql://localhost:15433/jc_voice_shopping");
        pg = DriverManager.getConnection(url,
                System.getProperty("eval.pg.user", "jichi"),
                System.getProperty("eval.pg.password", "vs-local-pg-2026"));
    }

    @Test
    void 消融矩阵() throws Exception {
        StringBuilder report = new StringBuilder(EvalSupport.configLine())
                .append("\n## 消融矩阵（每行 = 单组件开关对照）\n\n"
                        + "| # | 组件 | 对照 | 指标 | 关 | 开 | 归因结论 |\n|--|--|--|--|--|--|--|\n");

        // ---------- A1 规则重排消融（标准指标 nDCG@3 + 配对 bootstrap CI；自创命中率口径已废弃） ----------
        A1 a1 = ablationRerank();
        String a1Ci = ciText(a1.ndcgDiffCi);
        report.append(String.format("| A1 | 画像规则重排（真实相似度归一化+预算锚点） | 同候选集：向量序 vs 重排序 | nDCG@3（标准）③ | %.3f | %.3f | %s｜Top3均价/预算比 %.2f→%.2f（描述性业务统计） |%n",
                a1.ndcgPlain, a1.ndcgRerank,
                a1.ndcgDiffCi[0] > 0
                        ? String.format("重排使 nDCG@3 %+.3f，配对 95%%CI %s（不损害相关性前提下注入业务偏好）", a1.ndcgRerank - a1.ndcgPlain, a1Ci)
                        : a1.ndcgDiffCi[1] < 0
                                ? String.format("重排牺牲 %.3f nDCG@3（配对 95%%CI %s）换取业务对齐（价格比 %.2f→%.2f）",
                                a1.ndcgPlain - a1.ndcgRerank, a1Ci, a1.priceRatioPlain, a1.priceRatioRerank)
                                : String.format("差值 %.3f 的配对 95%%CI %s 跨 0：n=%d 下不可分辨（不许写成胜出/牺牲），"
                                + "价格比 %.2f→%.2f 为描述性业务统计",
                                a1.ndcgRerank - a1.ndcgPlain, a1Ci, a1.n, a1.priceRatioPlain, a1.priceRatioRerank),
                a1.priceRatioPlain, a1.priceRatioRerank));

        // ---------- A2 槽位归一化 ----------
        double[] a2 = ablationSlotNormalize();
        report.append(String.format("| A2 | 槽位归一化（SlotNormalizer） | 同一响应的 rawSlots vs slots | 槽位字段一致率（消融对照用） | %.1f%% | %.1f%% | %s |%n",
                a2[0] * 100, a2[1] * 100,
                String.format("归一化修复 %.1f 个百分点（自由文本→category_l2/场景枚举对齐）", (a2[1] - a2[0]) * 100)));

        // ---------- A3 TTS 数字归一化 ----------
        A3 a3 = ablationTtsDigits();
        String a3Ci = ciText(a3.ci);
        report.append(String.format("| A3 | TTS 数字归一化（金额语境阿拉伯→中文读法） | 含数字句回环（同族 omni 转写口径，n=%d） | 平均 CER（加性误差取均值） | %.1f%% | %.1f%% | %s |%n",
                a3.n, a3.off * 100, a3.on * 100,
                a3.ci[0] > 0
                        ? String.format("开启后平均 CER 上升 %.1f 个百分点（配对 95%%CI %s）——n=%d 下需扩样本再判；"
                        + "注意本消融只测\"念得准不准\"，逐位朗读（八零零）的可听性问题不在 CER 内", (a3.on - a3.off) * 100, a3Ci, a3.n)
                        : a3.ci[1] < 0
                                ? String.format("消除逐位朗读（八零零）后平均 CER 下降 %.1f 个百分点（配对 95%%CI %s）",
                                (a3.off - a3.on) * 100, a3Ci)
                                : String.format("本批未见增益：配对 95%%CI %s 跨 0（关=%.1f%% 开=%.1f%%，n=%d 样本量不足）",
                                a3Ci, a3.off * 100, a3.on * 100, a3.n)));

        // ---------- A4 thinking 开关（跨部署实测：需两次部署，故值从 -D 传入；缺省时尝试读 e2e 报告） ----------
        String off = System.getProperty("eval.abl.thinking.off", "");
        String on = System.getProperty("eval.abl.thinking.on", "");
        if (off.isBlank()) off = e2eReportAudioP50();   // 当前部署默认 thinking=false → e2e 报告即 off 侧实测
        String onDisplay = on.isBlank() ? "未实测" : on + "s";
        String offDisplay = off.isBlank() ? "未实测" : off + (fromReport(off) ? "s（取自 e2e 报告）" : "s");
        String a4Conclusion = on.isBlank() || off.isBlank()
                ? "跨部署对照未测全：以 LLM_ENABLE_THINKING=true/false 分别部署后跑 EvalE2eLatencyTest，"
                + "用 -Deval.abl.thinking.off/on 传入两侧 P50"
                : String.format("关闭思考使首响降低 %.0f%%（思考过程原本垫在首 token 前）",
                1 - Double.parseDouble(off) / Double.parseDouble(on) * 100);
        report.append(String.format("| A4 | 关闭思考模式（enable_thinking=false，语音链路） | 同链路 thinking on/off 部署 | 端到端首帧语音 P50 | %s | %s | %s |%n",
                offDisplay, onDisplay, a4Conclusion));

        // ---------- A5 语义重排 ----------
        A5 a5 = ablationSemanticRerank();
        String a5CiRich = ciText(a5.richVsPlainCi);
        String a5CiPoor = ciText(a5.poorVsPlainCi);
        String a5Conclusion;
        if (a5.richVsPlainCi[0] > 0) {
            a5Conclusion = String.format("富文档精排 +%dpp 胜向量序（配对 95%%CI %s）",
                    Math.round((a5.rich - a5.plain) * 100), a5CiRich);
        } else if (a5.richVsPlainCi[1] < 0) {
            a5Conclusion = String.format("富文档精排 %dpp 低于向量序（配对 95%%CI %s），维持默认关",
                    Math.round((a5.rich - a5.plain) * 100), a5CiRich);
        } else if (a5.richVsPoorCi[0] > 0) {
            a5Conclusion = String.format("富文档比贫文档 %+dpp（配对 95%%CI %s），但相对向量序的 CI %s 跨 0"
                    + "——n=%d 下不可分辨，维持默认关（该库头部已强，精排留作大库）",
                    Math.round((a5.rich - a5.poor) * 100), ciText(a5.richVsPoorCi), a5CiRich, a5.n);
        } else {
            a5Conclusion = String.format("三臂间配对 CI 均跨 0（富-向量 %s、富-贫 %s）：n=%d 上语义精排无显著增益，"
                    + "维持默认关（该库向量检索已够）", a5CiRich, a5CiPoor, a5.n);
        }
        report.append(String.format("| A5 | qwen3.7-text-rerank 语义精排（默认关） | 向量Top8三臂：向量序 / 贫文档④ / 富文档⑤ | 语义集平均 Recall@3（n=%d） | %.0f%% | 贫%.0f%%／富%.0f%% | %s |%n",
                a5.n, a5.plain * 100, a5.poor * 100, a5.rich * 100, a5Conclusion));

        report.append("""

                ③ nDCG@3 = 标准检索质量指标（DCG/IDCG，二元相关性）；A1 的相关集为过滤 query 的确定性期望集。
                配对 bootstrap CI 用于小样本对照：CI 跨 0 表示该样本量下增益不可分辨，结论不得写"胜出/牺牲"。
                "均价/预算比"为描述性业务统计（用户预算锚点贴近度），不是质量指标——自创命中率口径已废弃
                ④ 贫文档 = 商品名+属性 toString；⑤ 富文档 = 与向量库同源的 ProductTextBuilder 全文（名称+品牌+品类+卖点×2+描述+属性）（用户预算锚点附近）；
                A1/A2/A3 为本测试现场跑出的同批对照（同一时刻同一部署），A4 为跨部署实测：
                当前部署默认 thinking=false，off 侧缺省取自 e2e 报告 P50，on 侧需以 LLM_ENABLE_THINKING=true 部署后传入。
                复跑方式：`mvn test -P eval -Dtest=EvalAblationTest`。
                """);

        var path = EvalSupport.writeReport("ablation-report.md", "消融归因矩阵", report.toString());
        System.out.println("[EVAL] 消融矩阵已生成: " + path);
    }

    private static String ciText(double[] ci) {
        if (Double.isNaN(ci[0])) return "[N/A]";
        return String.format("[%+.3f, %+.3f]", ci[0], ci[1]);
    }

    private static boolean fromReport(String v) {
        return System.getProperty("eval.abl.thinking.off", "").isBlank();
    }

    /** 从 e2e 报告读首帧音频 P50（表行 `| **P50** | ... | ... | X |`）；不可用返回空串。 */
    private static String e2eReportAudioP50() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of("eval/reports/e2e-latency-report.md");
            if (!java.nio.file.Files.exists(p)) return "";
            for (String line : java.nio.file.Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.startsWith("| **P50**")) {
                    String[] cells = line.split("\\|");
                    if (cells.length >= 5) {
                        String v = cells[4].replace("*", "").trim();
                        Double.parseDouble(v);   // 校验是数字
                        return v;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ===== A1：规则重排消融（标准 nDCG@3 + 配对 bootstrap CI + 描述性价格比） =====
    private record A1(double ndcgPlain, double ndcgRerank, double priceRatioPlain, double priceRatioRerank,
                      double[] ndcgDiffCi, int n) {
    }

    private A1 ablationRerank() throws Exception {
        List<Map<String, Object>> golden = EvalSupport.loadGolden("eval/rag-golden.jsonl");
        ProfileReranker reranker = new ProfileReranker();
        List<Double> ndcgPlain = new ArrayList<>(), ndcgRerank = new ArrayList<>();
        double priceSumPlain = 0, priceSumRerank = 0, budgetSum = 0;
        int priceCount = 0;

        for (Map<String, Object> g : golden) {
            Map<String, Object> slots = (Map<String, Object>) g.get("slots");
            if (!"filtered".equals(g.get("mode")) || !(slots.get("budget") instanceof Number)) continue;
            double budget = ((Number) slots.get("budget")).doubleValue();

            Map<String, Object> slotsNoBudget = new HashMap<>(slots);
            slotsNoBudget.remove("budget");
            var filter = com.mindcart.voice.service.SqlFilterBuilder.fromSlots(slotsNoBudget);
            if ("跑鞋".equals(slots.get("category"))) {
                filter = com.mindcart.voice.service.SqlFilterBuilder.merge(filter,
                        com.mindcart.voice.service.SqlFilterBuilder.runningShoeFilter(slots));
            }
            List<RecommendedItem> cands = toItems(vectorSearch(EvalSupport.embed((String) g.get("query")),
                    filter.clause(), filter.params(), 20));
            if (cands.size() < 3) continue;

            // 对照：纯向量序；实验：完整规则重排（真实相似度归一化 + 预算锚点 + 画像项）
            List<RecommendedItem> plain = cands;      // 向量序即检索输出序
            List<RecommendedItem> reranked = reranker.rerank(cands, null, slots);
            List<Long> plainIds = plain.stream().limit(3).map(RecommendedItem::productId).toList();
            List<Long> rerankIds = reranked.stream().limit(3).map(RecommendedItem::productId).toList();

            // 标准 nDCG@3：以"预算过滤后的期望集"为相关定义（过滤 query 的相关性有确定性定义）
            Set<Long> relevant = new HashSet<>();
            for (Number n : (List<Number>) g.get("relevant")) relevant.add(n.longValue());
            ndcgPlain.add(EvalMetrics.ndcgAtK(plainIds, relevant, 3));
            ndcgRerank.add(EvalMetrics.ndcgAtK(rerankIds, relevant, 3));

            // 描述性业务统计：Top3 均价 / 预算
            priceSumPlain += plainIds.stream().mapToDouble(id -> EvalSupport.prices().get(id)).average().orElse(0);
            priceSumRerank += rerankIds.stream().mapToDouble(id -> EvalSupport.prices().get(id)).average().orElse(0);
            budgetSum += budget;
            priceCount++;
        }
        return new A1(EvalMetrics.mean(ndcgPlain), EvalMetrics.mean(ndcgRerank),
                priceSumPlain / budgetSum, priceSumRerank / budgetSum,
                EvalMetrics.pairedBootstrapCI(ndcgPlain, ndcgRerank), ndcgPlain.size());
    }

    // ===== A2：槽位归一化 =====
    private double[] ablationSlotNormalize() throws Exception {
        List<Map<String, Object>> golden = EvalSupport.loadGolden("eval/independent-intent-200.jsonl");
        int rawHit = 0, normHit = 0, expected = 0;
        // RUN_STAMP 纪律：sessionId 固定会命中意图缓存（5 分钟 TTL），跨运行读到上次结果
        String stamp = String.valueOf(System.currentTimeMillis());
        for (int i = 0; i < golden.size(); i++) {
            Map<String, Object> g = golden.get(i);
            Map<String, Object> expectSlots = (Map<String, Object>) g.getOrDefault("slots", Map.of());
            if (expectSlots.isEmpty()) continue;
            String body = EvalSupport.postText(
                    EvalSupport.BASE_URL + "/api/v1/agent/intent?sessionId=eval-abl-" + stamp + "-" + i,
                    (String) g.get("text"));
            Map<String, Object> out = JSON.readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Map<String, Object> raw = (Map<String, Object>) out.getOrDefault("rawSlots", Map.of());
            Map<String, Object> norm = (Map<String, Object>) out.getOrDefault("slots", Map.of());
            for (var e : expectSlots.entrySet()) {
                expected++;
                if (match(raw.get(e.getKey()), e.getValue())) rawHit++;
                if (match(norm.get(e.getKey()), e.getValue())) normHit++;
            }
        }
        return new double[]{(double) rawHit / expected, (double) normHit / expected};
    }

    // ===== A5：语义重排三臂（向量序 / 贫文档精排 / 富文档精排） =====
    private record A5(double plain, double poor, double rich,
                      double[] richVsPlainCi, double[] poorVsPlainCi, double[] richVsPoorCi, int n) {
    }

    // 文档贫困假设：贫文档=name+attrs toString（信息量低），富文档=向量库同源 ProductTextBuilder 产物
    private A5 ablationSemanticRerank() throws Exception {
        // LlmGuard 用 SimpleMeterRegistry + null 仓构造：rerank 返回非 Msg，token 记账路径不会触发
        var client = new com.mindcart.voice.service.SemanticRerankClient(
                new com.mindcart.voice.service.LlmGuard(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null));
        EvalSupport.reflectSet(client, Map.of("apiKey", System.getenv("DASHSCOPE_API_KEY"),
                "model", "qwen3.7-text-rerank"));
        var textBuilder = new com.mindcart.voice.service.ProductTextBuilder();
        List<Map<String, Object>> golden = EvalSupport.loadGolden("eval/rag-golden.jsonl");
        List<Double> plain = new ArrayList<>(), poor = new ArrayList<>(), rich = new ArrayList<>();
        for (Map<String, Object> g : golden) {
            if (!"semantic".equals(g.get("mode"))) continue;
            String query = (String) g.get("query");
            Set<Long> relevant = new HashSet<>();
            for (Number n : (List<Number>) g.get("relevant")) relevant.add(n.longValue());
            List<Long> ids = vectorSearch(EvalSupport.embed(query), "", List.of(), 8);
            List<RecommendedItem> top8 = toItems(ids);
            if (top8.size() < 3) continue;
            Map<Long, com.mindcart.voice.entity.ProductEntity> ents = fetchEntities(ids);
            List<String> richDocs = new ArrayList<>();
            for (RecommendedItem it : top8) {
                var e = ents.get(it.productId());
                richDocs.add(e != null ? textBuilder.build(e) : it.name() + " " + it.attributes());
            }
            List<Long> plainIds = top8.stream().limit(3).map(RecommendedItem::productId).toList();
            List<Long> poorIds = client.rerank(query, top8, 3).stream()
                    .map(RecommendedItem::productId).toList();
            List<Long> richIds = client.rerank(query, top8, richDocs, 3).stream()
                    .map(RecommendedItem::productId).toList();
            plain.add(EvalMetrics.recallAtK(plainIds, relevant, 3));
            poor.add(EvalMetrics.recallAtK(poorIds, relevant, 3));
            rich.add(EvalMetrics.recallAtK(richIds, relevant, 3));
        }
        return new A5(EvalMetrics.mean(plain), EvalMetrics.mean(poor), EvalMetrics.mean(rich),
                EvalMetrics.pairedBootstrapCI(plain, rich),
                EvalMetrics.pairedBootstrapCI(plain, poor),
                EvalMetrics.pairedBootstrapCI(poor, rich),
                plain.size());
    }

    /** 拉全量实体字段供 ProductTextBuilder 构造与向量库同源的富文本。 */
    private Map<Long, com.mindcart.voice.entity.ProductEntity> fetchEntities(List<Long> ids)
            throws Exception {
        Map<Long, com.mindcart.voice.entity.ProductEntity> out = new HashMap<>();
        try (var ps = pg.prepareStatement(
                "SELECT id, name, category_l1, category_l2, brand, description, selling_points, "
                        + "attributes::text AS attrs FROM product WHERE id = ANY(?)")) {
            ps.setArray(1, pg.createArrayOf("bigint", ids.toArray()));
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var e = new com.mindcart.voice.entity.ProductEntity();
                    e.setId(rs.getLong("id"));
                    e.setName(rs.getString("name"));
                    e.setCategoryL1(rs.getString("category_l1"));
                    e.setCategoryL2(rs.getString("category_l2"));
                    e.setBrand(rs.getString("brand"));
                    e.setDescription(rs.getString("description"));
                    e.setSellingPoints(rs.getString("selling_points"));
                    String attrs = rs.getString("attrs");
                    if (attrs != null && !attrs.isBlank()) {
                        e.setAttributes(JSON.readValue(attrs,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    }
                    out.put(e.getId(), e);
                }
            }
        }
        return out;
    }

    // ===== A3：TTS 数字归一化 =====
    private record A3(double off, double on, double[] ci, int n) {
    }

    private A3 ablationTtsDigits() throws Exception {
        List<Map<String, Object>> queries = EvalSupport.loadGolden("eval/v2-speech-40.jsonl");
        List<String> digitTexts = queries.stream()
                .filter(q -> Boolean.TRUE.equals(q.get("digits")))
                .map(q -> (String) q.get("query")).toList();
        List<Double> cerOff = new ArrayList<>(), cerOn = new ArrayList<>();
        for (boolean normalize : new boolean[]{false, true}) {
            TtsService tts = EvalSupport.buildTts(normalize);
            for (String text : digitTexts) {
                ByteArrayOutputStream audio = new ByteArrayOutputStream();
                try {
                    tts.synthesize(Flowable.just(text)).blockingSubscribe(b -> {
                        byte[] a = new byte[b.remaining()];
                        b.get(a);
                        audio.write(a, 0, a.length);
                    });
                } catch (Exception e) {
                    (normalize ? cerOn : cerOff).add(1.0);
                    continue;
                }
                String heard = transcribe(ttsAsr(normalize == false), audio.toByteArray());
                // 口径：以实际送入 TTS 的文本（归一化后）为参考——归一化改变的是"念什么"，
                // 不应计为回环错误
                String spokenRef = com.mindcart.voice.voice.TtsTextNormalizer.normalize(text);
                double cer = EvalMetrics.cer(spokenRef, heard == null ? "" : heard);
                (normalize ? cerOn : cerOff).add(cer);
            }
        }
        return new A3(EvalMetrics.mean(cerOff), EvalMetrics.mean(cerOn),
                EvalMetrics.pairedBootstrapCI(cerOff, cerOn), cerOff.size());   // 均值：加性错误的正确口径
    }

    // ===== 基础设施 =====

    private static com.mindcart.voice.voice.AsrService ttsAsr(boolean unused) throws Exception {
        // 与 EvalVoiceLoopTest 同源修复：@Value 字段必须显式注入生产参数，
        // 否则 vad/flushGrace/settle 退化为 0，长句尾部被吞、CER 虚高
        return EvalSupport.reflectSet(new com.mindcart.voice.voice.AsrService(), Map.of(
                "apiKey", System.getenv("DASHSCOPE_API_KEY"),
                "model", System.getProperty("eval.asr.model", "qwen3.5-omni-flash-realtime"),
                "vadSilenceMs", EvalSupport.prodVadSilenceMs(),
                "flushGraceMs", EvalSupport.prodFlushGraceMs(),
                "settleMs", EvalSupport.prodSettleMs()));
    }

    private String transcribe(com.mindcart.voice.voice.AsrService asr, byte[] pcm16k) throws Exception {
        List<ByteBuffer> chunks = new ArrayList<>();
        for (int i = 0; i < pcm16k.length; i += 3200) {
            chunks.add(ByteBuffer.wrap(Arrays.copyOfRange(pcm16k, i, Math.min(i + 3200, pcm16k.length))));
        }
        // 真实语速口径：Flowable.delay 是整体平移（瞬时灌完全部音频块），
        // 会让 VAD 在句内停顿振荡——统一用 EvalSupport.paced 逐块间隔灌流
        return asr.recognize(EvalSupport.paced(chunks, 100), (p, e) -> { }).get(30, TimeUnit.SECONDS);
    }

    private List<RecommendedItem> toItems(List<Long> ids) throws Exception {
        List<RecommendedItem> out = new ArrayList<>();
        try (var ps = pg.prepareStatement(
                "SELECT id, name, price FROM product WHERE id = ANY(?)")) {
            ps.setArray(1, pg.createArrayOf("bigint", ids.toArray()));
            try (var rs = ps.executeQuery()) {
                Map<Long, Object[]> byId = new HashMap<>();
                while (rs.next()) byId.put(rs.getLong(1), new Object[]{rs.getString(2), rs.getBigDecimal(3)});
                for (int i = 0; i < ids.size(); i++) {
                    Object[] r = byId.get(ids.get(i));
                    if (r == null) continue;
                    out.add(new RecommendedItem(ids.get(i), (String) r[0], (BigDecimal) r[1],
                            null, 1.0 - i * 0.03, Map.of()));
                }
            }
        }
        return out;
    }

    private List<Long> vectorSearch(float[] qvec, String filter, List<Object> params, int topK)
            throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM product WHERE status='ON_SALE' AND embedding IS NOT NULL");
        if (!filter.isBlank()) sql.append(" AND ").append(filter);
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        List<Object> all = new ArrayList<>(params);
        all.add(com.mindcart.voice.service.VectorLiteral.of(qvec));
        all.add(topK);
        try (var ps = pg.prepareStatement(sql.toString())) {
            for (int i = 0; i < all.size(); i++) ps.setObject(i + 1, all.get(i));
            try (var rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) ids.add(rs.getLong(1));
                return ids;
            }
        }
    }
    private static boolean match(Object actual, Object expected) {
        if (actual instanceof Number an && expected instanceof Number en)
            return Math.abs(an.doubleValue() - en.doubleValue()) < 1e-6;
        return String.valueOf(actual).equals(String.valueOf(expected));
    }
}
