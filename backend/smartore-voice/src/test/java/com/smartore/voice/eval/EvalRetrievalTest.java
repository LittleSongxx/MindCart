package com.smartore.voice.eval;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索评测：
 *  - semantic：无过滤向量检索 vs 人工标注相关集 → Recall@5/@10、MRR、nDCG@10；
 *    另以 Java 全量余弦（30 商品小库可精确算）为真值，度量 HNSW 近似检索的召回损失。
 *  - filtered：槽位标量过滤 + 向量排序（与生产同一条 SQL）→ 结果集与确定性期望做精确集合相等。
 * 依赖：compose 的 PG（默认 localhost:15433）+ DASHSCOPE_API_KEY（embedding）。
 */
@Tag("eval")
class EvalRetrievalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Connection pg;
    private static Map<Long, float[]> embeddings;   // 商品向量真值池

    @BeforeAll
    static void guard() throws Exception {
        Assumptions.assumeTrue(EvalSupport.apiKeyPresent(), "需要 DASHSCOPE_API_KEY");
        String url = System.getProperty("eval.pg.url", "jdbc:postgresql://localhost:15433/jc_voice_shopping");
        String user = System.getProperty("eval.pg.user", "jichi");
        String pass = System.getProperty("eval.pg.password", "vs-local-pg-2026");
        pg = DriverManager.getConnection(url, user, pass);
        embeddings = loadEmbeddings();
        Assumptions.assumeTrue(embeddings.size() >= 30,
                "商品向量缺失（" + embeddings.size() + "/30），请先跑 reindex：" + EvalSupport.BASE_URL + "/api/v1/admin/reindex");
    }

    @Test
    void 检索评测() throws Exception {
        List<Map<String, Object>> golden = EvalSupport.loadGolden("eval/rag-golden.jsonl");
        StringBuilder report = new StringBuilder();
        List<Double> r3 = new ArrayList<>(), r5 = new ArrayList<>(), r10 = new ArrayList<>(),
                n3 = new ArrayList<>(), mrrs = new ArrayList<>(), ndcgs = new ArrayList<>(),
                annRecalls = new ArrayList<>();
        int filteredPass = 0, filteredTotal = 0, emptyOk = 0;
        // 负例（金标相关集为空）：Recall/nDCG 在该构造下未定义（EvalMetrics 记 1.0 会让均值虚增），
        // 单独统计"期望集为空"的条目并从检索均值中剔除，避免无声抬高汇总
        int negatives = 0;
        List<String> negativeQueries = new ArrayList<>();

        // ---- semantic ----
        report.append("## 语义检索（无过滤，Top10；@3 为用户实际可见口径）\n\n| Query | 期望 | R@3 | Recall@5 | Recall@10 | MRR | nDCG@3 | nDCG@10 | ANN召回损失 |\n|--|--|--|--|--|--|--|--|--|\n");
        for (Map<String, Object> g : golden) {
            if (!"semantic".equals(g.get("mode"))) continue;
            String query = (String) g.get("query");
            Set<Long> relevant = toSet((List<Number>) g.get("relevant"));
            float[] qvec = EvalSupport.embed(query);

            List<Long> viaHnsw = vectorSearch(qvec, "", List.of(), 10);
            List<Long> truth = bruteForceTopK(qvec, 10);
            Set<Long> truthSet = new LinkedHashSet<>(truth);
            long overlap = viaHnsw.stream().filter(truthSet::contains).count();
            annRecalls.add(overlap / 10.0);

            if (relevant.isEmpty()) {
                // 负例：无相关集 → 指标无定义，只登记不入均值
                negatives++;
                negativeQueries.add(query);
                report.append(String.format("| %s | （负例，无相关标注） | - | - | - | - | - | - | %.0f%% |%n",
                        query, overlap * 10.0));
                continue;
            }
            double recall3 = EvalMetrics.recallAtK(viaHnsw, relevant, 3);
            double recall5 = EvalMetrics.recallAtK(viaHnsw, relevant, 5);
            double recall10 = EvalMetrics.recallAtK(viaHnsw, relevant, 10);
            double mrr = EvalMetrics.mrr(viaHnsw, relevant);
            double ndcg3 = EvalMetrics.ndcgAtK(viaHnsw, relevant, 3);
            double ndcg = EvalMetrics.ndcgAtK(viaHnsw, relevant, 10);
            r3.add(recall3); r5.add(recall5); r10.add(recall10); n3.add(ndcg3);
            mrrs.add(mrr); ndcgs.add(ndcg);
            report.append(String.format("| %s | %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.0f%% |%n",
                    query, g.get("relevant"), recall3, recall5, recall10, mrr, ndcg3, ndcg, overlap * 10.0));
        }
        report.append(String.format("%n**语义汇总**（有效 n=%d；负例 %d 条不计入均值——无相关集时 Recall 未定义）："
                        + "Recall@3 均值 **%.3f**｜Recall@5 均值 **%.3f**｜Recall@10 均值 **%.3f**｜MRR 均值 **%.3f**｜"
                        + "nDCG@3 均值 **%.3f**｜nDCG@10 均值 **%.3f**｜HNSW 相对精确检索召回 **%.1f%%**（30 商品小库，工程检查项）%n",
                r5.size(), negatives, EvalMetrics.mean(r3), EvalMetrics.mean(r5), EvalMetrics.mean(r10), EvalMetrics.mean(mrrs), EvalMetrics.mean(n3), EvalMetrics.mean(ndcgs), EvalMetrics.mean(annRecalls) * 100));
        if (!negativeQueries.isEmpty()) {
            report.append("负例（目录外需求，期望集为空）：").append(String.join("、", negativeQueries))
                    .append("——正确行为由澄清/越界分支承接，不适用检索召回指标\n");
        }

        // ---- filtered ----
        report.append("\n## 过滤检索（槽位标量过滤 + 向量排序，精确集合匹配）\n\n| Query | 期望 | 实际 | 结果 |\n|--|--|--|--|\n");
        for (Map<String, Object> g : golden) {
            if (!"filtered".equals(g.get("mode"))) continue;
            filteredTotal++;
            String query = (String) g.get("query");
            Map<String, Object> slots = (Map<String, Object>) g.get("slots");
            Set<Long> expected = toSet((List<Number>) g.get("relevant"));

            // 与生产 RecommendCandidatesService 相同的过滤组装：跑鞋叠加场景映射
            var filter = com.smartore.voice.service.SqlFilterBuilder.fromSlots(slots);
            if ("跑鞋".equals(slots.get("category"))) {
                filter = com.smartore.voice.service.SqlFilterBuilder.merge(filter,
                        com.smartore.voice.service.SqlFilterBuilder.runningShoeFilter(slots));
            }
            List<Long> actual = vectorSearch(EvalSupport.embed(query), filter.clause(), filter.params(), 20);
            Set<Long> actualSet = new LinkedHashSet<>(actual);
            boolean pass = actualSet.equals(expected);
            if (pass) filteredPass++;
            if (expected.isEmpty() && actualSet.isEmpty()) emptyOk++;
            report.append(String.format("| %s | %s | %s | %s |%n",
                    query, new ArrayList<>(expected), actual, pass ? "✅" : "❌"));
        }
        report.append(String.format("%n**过滤汇总**：精确匹配 **%d/%d**（其中空结果正确 %d 条）%n",
                filteredPass, filteredTotal, emptyOk));
        assertTrue(filteredPass >= filteredTotal - 1, "过滤检索允许最多 1 条标注争议，实际未过 " + (filteredTotal - filteredPass) + " 条");

        var path = EvalSupport.writeReport("rag-report.md", "RAG 检索评测报告",
                EvalSupport.configLine() + "\n" + report
                        + "\n## 口径\n- semantic：无槽位，`ORDER BY embedding <=>` 取 Top10 对人工标注集算指标；ANN 召回损失 = HNSW Top10 与 Java 全量余弦 Top10 的重合率\n- 负例（相关集为空）不计入 Recall/nDCG 均值：无相关集时该指标未定义，混入会虚增汇总（其正确行为由澄清/越界分支承接）\n- filtered：与生产同一 SQL（标量过滤 + 向量排序 + iterative_scan 取满 LIMIT 20），期望集为确定性过滤结果，要求精确相等\n");
        System.out.println("[EVAL] RAG 报告已生成: " + path);
    }

    // ===== 基础设施 =====

    private static Map<Long, float[]> loadEmbeddings() throws Exception {
        Map<Long, float[]> out = new HashMap<>();
        try (var st = pg.createStatement(); var rs = st.executeQuery(
                "SELECT id, embedding::text FROM product WHERE embedding IS NOT NULL")) {
            while (rs.next()) {
                String t = rs.getString(2);
                String[] parts = t.replaceAll("[\\[\\]]", "").split(",");
                float[] v = new float[parts.length];
                for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i].trim());
                out.put(rs.getLong(1), v);
            }
        }
        return out;
    }

    /** 与生产 ProductVectorService 同构的 HNSW 检索。 */
    private static List<Long> vectorSearch(float[] qvec, String filter, List<Object> params, int topK)
            throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM product WHERE status='ON_SALE' AND embedding IS NOT NULL");
        if (!filter.isBlank()) sql.append(" AND ").append(filter);
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        List<Object> all = new ArrayList<>(params);
        all.add(com.smartore.voice.service.VectorLiteral.of(qvec));
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

    private static List<Long> bruteForceTopK(float[] q, int k) {
        record Scored(long id, double s) {}
        return embeddings.entrySet().stream()
                .map(e -> new Scored(e.getKey(), cosine(q, e.getValue())))
                .sorted((a, b) -> Double.compare(b.s(), a.s()))
                .limit(k).map(Scored::id).toList();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-10);
    }

    private static Set<Long> toSet(List<Number> nums) {
        Set<Long> s = new LinkedHashSet<>();
        for (Number n : nums) s.add(n.longValue());
        return s;
    }}
