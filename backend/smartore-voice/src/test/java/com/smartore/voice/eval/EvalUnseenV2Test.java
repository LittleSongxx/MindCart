package com.smartore.voice.eval;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * 未见集 v2 封存跑法（独立出题、去污染、先定题后跑分）。
 * <p>
 * 纪律（见 eval/README.md 台账）：
 * <ul>
 *   <li>本报告**只输出总分与分类汇总**，不输出逐条明细——读明细即视为"本批开始消费"，
 *       须在台账记 {@code 已消费@日期} 并降级为回归集。</li>
 *   <li>三个文件已冻结（只读 + 报告头部记录 sha256）；修改集合本身即作废本批。</li>
 *   <li>对照口径（首次封存跑）：单轮 92.0% / 多轮 95.0%；规则改进后 97.0% / 98.3%。</li>
 * </ul>
 * 数据来源：{@code independent-intent-200.jsonl}（单轮 200）、{@code independent-intent-multiturn-60.prod.jsonl}
 * （多轮 60，已转生产 TURN 格式）、{@code v2-speech-40.jsonl}（话术 40）。
 */
@Tag("eval")
class EvalUnseenV2Test {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RUN_STAMP = String.valueOf(System.currentTimeMillis());
    private static final long EVAL_USER = 4L;
    private static final Path SINGLE = Path.of("eval/independent-intent-200.jsonl");
    private static final Path MULTI = Path.of("eval/independent-intent-multiturn-60.prod.jsonl");
    private static final Path SPEECH = Path.of("eval/v2-speech-40.jsonl");
    private static final List<String> FORBIDDEN = List.of(
            "最好", "最便宜", "第一", "保证", "绝对", "肯定", "全网最低", "史上最");

    @BeforeAll
    static void guard() {
        Assumptions.assumeTrue(EvalSupport.serviceUp(), "服务未启动");
        Assumptions.assumeTrue(Files.exists(SINGLE) && Files.exists(MULTI) && Files.exists(SPEECH),
                "未见集 v2 文件缺失");
    }

    @Test
    void 未见集v2总评() throws Exception {
        StringBuilder report = new StringBuilder(EvalSupport.configLine()).append("\n")
                .append("> 封存指纹（sha256）：\n")
                .append("> - `independent-intent-200.jsonl`: ").append(sha256(SINGLE)).append("\n")
                .append("> - `independent-intent-multiturn-60.prod.jsonl`: ").append(sha256(MULTI)).append("\n")
                .append("> - `v2-speech-40.jsonl`: ").append(sha256(SPEECH)).append("\n")
                .append("\n> **本报告只出总分**：按封存纪律，读逐条明细即视为消费本批。\n");

        // ---- 1) 单轮 200：准确率 + CI + 分意图 + 槽位 micro ----
        List<Map<String, Object>> single = EvalSupport.loadGolden(SINGLE.toString());
        List<Double> hits = new ArrayList<>();
        Map<String, int[]> perClass = new LinkedHashMap<>();   // class -> [hit, total]
        Map<String, int[]> slotStats = new LinkedHashMap<>();
        int parseFail = 0;
        for (int i = 0; i < single.size(); i++) {
            Map<String, Object> g = single.get(i);
            String body = EvalSupport.postText(
                    EvalSupport.BASE_URL + "/api/v1/agent/intent?sessionId=v2-single-" + RUN_STAMP + "-" + i,
                    String.valueOf(g.get("text")));
            try {
                Map<String, Object> out = JSON.readValue(body,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                String expect = String.valueOf(g.get("intent"));
                String actual = String.valueOf(out.get("intent"));
                hits.add(expect.equals(actual) ? 1.0 : 0.0);
                int[] pc = perClass.computeIfAbsent(expect, k -> new int[2]);
                pc[1]++;
                if (expect.equals(actual)) pc[0]++;
                accumulateSlots(slotStats, g, out);
            } catch (Exception e) {
                parseFail++;
                hits.add(0.0);
            }
        }
        double acc = EvalMetrics.mean(hits);
        double[] ci = EvalMetrics.bootstrapCI(hits);
        report.append(String.format("%n## 一、单轮意图（n=%d）%n%n意图准确率：**%.1f%%**（%.0f/%d），95%% CI [%.1f%%, %.1f%%]，解析失败 %d%n",
                hits.size(), acc * 100, hits.stream().mapToDouble(Double::doubleValue).sum(), hits.size(),
                ci[0] * 100, ci[1] * 100, parseFail));
        report.append("\n| 意图 | 命中/总数 | 准确率 |\n|--|--|--|\n");
        perClass.forEach((k, v) -> report.append(String.format("| %s | %d/%d | %.0f%% |%n", k, v[0], v[1], v[0] * 100.0 / v[1])));
        int mTp = 0, mFp = 0, mFn = 0;
        for (int[] st : slotStats.values()) {
            mTp += st[0];
            mFp += st[1];
            mFn += st[2];
        }
        double p = mTp + mFp == 0 ? 1 : (double) mTp / (mTp + mFp);
        double r = mTp + mFn == 0 ? 1 : (double) mTp / (mTp + mFn);
        report.append(String.format("%n槽位 micro：**P=%.3f R=%.3f F1=%.3f**（TP=%d FP=%d FN=%d）%n",
                p, r, p + r == 0 ? 0 : 2 * p * r / (p + r), mTp, mFp, mFn));

        // ---- 2) 多轮 60：生产 TURN 历史注入 ----
        List<Map<String, Object>> multi = EvalSupport.loadGolden(MULTI.toString());
        List<Double> mHits = new ArrayList<>();
        Map<String, int[]> mClass = new LinkedHashMap<>();
        for (int i = 0; i < multi.size(); i++) {
            Map<String, Object> g = multi.get(i);
            String sid = "v2-multi-" + RUN_STAMP + "-" + i;
            EvalSupport.postJson(EvalSupport.BASE_URL + "/api/v1/debug/memory/seed?sessionId=" + sid,
                    JSON.writeValueAsString(g.get("history")));
            String body = EvalSupport.postText(
                    EvalSupport.BASE_URL + "/api/v1/agent/intent?sessionId=" + sid, String.valueOf(g.get("text")));
            String expect = String.valueOf(g.get("expect"));
            String actual = "";
            try {
                actual = String.valueOf(JSON.readValue(body,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}).get("intent"));
            } catch (Exception ignored) {
            }
            mHits.add(expect.equals(actual) ? 1.0 : 0.0);
            int[] pc = mClass.computeIfAbsent(expect, k -> new int[2]);
            pc[1]++;
            if (expect.equals(actual)) pc[0]++;
        }
        double mAcc = EvalMetrics.mean(mHits);
        double[] mCi = EvalMetrics.bootstrapCI(mHits);
        report.append(String.format("%n## 二、多轮意图（n=%d，生产 TURN 历史）%n%n准确率：**%.1f%%**，95%% CI [%.1f%%, %.1f%%]%n",
                mHits.size(), mAcc * 100, mCi[0] * 100, mCi[1] * 100));
        report.append("\n| 意图 | 命中/总数 | 准确率 |\n|--|--|--|\n");
        mClass.forEach((k, v) -> report.append(String.format("| %s | %d/%d | %.0f%% |%n", k, v[0], v[1], v[0] * 100.0 / v[1])));

        // ---- 3) 话术 40：合规 / 格式 / 忠实度 + 覆盖率 ----
        List<Map<String, Object>> speech = EvalSupport.loadGolden(SPEECH.toString());
        int compliant = 0, wellFormed = 0, faithful = 0, withPrice = 0;
        for (Map<String, Object> q : speech) {
            String body = EvalSupport.postText(EvalSupport.BASE_URL + "/api/v1/chat?sessionId=v2-speech-"
                    + RUN_STAMP + "-" + q.get("id") + "&userId=" + EVAL_USER, String.valueOf(q.get("query")));
            String speechText;
            try {
                speechText = String.valueOf(JSON.readValue(body,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}).get("speechText"));
            } catch (Exception e) {
                continue;
            }
            if (FORBIDDEN.stream().noneMatch(speechText::contains)) compliant++;
            boolean fmt = !speechText.contains("{") && !speechText.contains("```") && !speechText.contains("**")
                    && !speechText.contains("|--") && !speechText.contains("\n")
                    && !speechText.matches("(?s).*^\\s*#{1,6}\\s.*")
                    && speechText.length() >= 6 && speechText.length() <= 300;
            if (fmt) wellFormed++;
            Set<Double> legal = legalPricesFor(String.valueOf(q.get("expectCategory")));
            SpeechPriceCheck.Verdict v = SpeechPriceCheck.check(speechText, legal);
            if (v.ok()) faithful++;
            if (hasPriceMention(speechText)) withPrice++;
        }
        int sn = speech.size();
        report.append(String.format("""
                %n## 三、话术质量（n=%d）%n%n| 维度 | 通过 | 覆盖率 |%n|--|--|--|%n| 合规（禁词零出现） | %d/%d | 全量 |%n| 格式（纯文本 6~300 字） | %d/%d | 全量 |%n| 忠实度（价格与库一致） | %d/%d | 含价格话术 **%d/%d** |%n""",
                sn, compliant, sn, wellFormed, sn, faithful, sn, withPrice, sn));

        report.append("""

                ## 口径
                - 单轮/多轮：`/api/v1/agent/intent`（纯分类器口径）；多轮注入生产同源 TURN 历史
                - 话术：`/api/v1/chat` 全链路产物；忠实度判定见 `SpeechPriceCheck`（含价格覆盖率披露）
                - 封存纪律：读逐条明细 = 开始消费本批（见 eval/README.md 台账）
                """);

        var path = EvalSupport.writeReport("v2-unseen-report.md", "未见集 v2 报告（封存口径·只出总分）", report.toString());
        System.out.printf("[EVAL] V2 未见集：单轮 %.1f%%｜多轮 %.1f%%｜话术 合规%d 格式%d 忠实%d（含价格%d）→ %s%n",
                acc * 100, mAcc * 100, compliant, wellFormed, faithful, withPrice, path);

        // 门槛（回归门禁）：本测试同时是全仓唯一的意图/话术自动门禁，故设**地板值**而非目标值——
        // 取下限留出波动余量（单轮 97.0%、多轮 98.3% 为最近实测），跌破即说明系统行为或链路出问题
        org.junit.jupiter.api.Assertions.assertTrue(acc >= 0.92,
                String.format("V2 单轮意图准确率 %.1f%% 低于 92%% 地板", acc * 100));
        org.junit.jupiter.api.Assertions.assertTrue(mAcc >= 0.90,
                String.format("V2 多轮意图准确率 %.1f%% 低于 90%% 地板", mAcc * 100));
        org.junit.jupiter.api.Assertions.assertEquals(sn, compliant, "存在违禁词泄露");
        org.junit.jupiter.api.Assertions.assertTrue(withPrice >= 3,
                "含价格话术过少（" + withPrice + "/" + sn + "），忠实度指标退化");
    }

    // ===== 工具 =====

    private static void accumulateSlots(Map<String, int[]> stats, Map<String, Object> gold, Map<String, Object> out) {
        Map<String, Object> expect = (Map<String, Object>) gold.getOrDefault("slots", Map.of());
        Map<String, Object> actual = (Map<String, Object>) out.getOrDefault("slots", Map.of());
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(expect.keySet());
        fields.addAll(actual.keySet());
        for (String f : fields) {
            Object g = expect.get(f), p = actual.get(f);
            if (g == null && p == null) continue;
            int[] st = stats.computeIfAbsent(f, k -> new int[3]);
            boolean eq = g != null && p != null && String.valueOf(g).equals(String.valueOf(p));
            if (eq) st[0]++;
            else if (p != null) st[1]++;
            if (g != null && !eq) st[2]++;
        }
    }

    private static boolean hasPriceMention(String speech) {
        return java.util.regex.Pattern.compile("(\\d{2,6})(?:元|块钱|块)").matcher(
                CnNum.toDigits(speech)).find()
                || java.util.regex.Pattern.compile("(\\d{2,6})(?=出头|左右|以内|上下)").matcher(
                CnNum.toDigits(speech)).find();
    }

    private static Set<Double> legalPricesFor(String category) {
        int from = switch (category) {
            case "跑鞋" -> 1;
            case "手表" -> 11;
            case "耳机" -> 21;
            default -> 26;
        };
        int to = switch (category) {
            case "跑鞋" -> 10;
            case "手表" -> 20;
            case "耳机" -> 25;
            default -> 30;
        };
        Set<Double> s = new HashSet<>();
        for (long id = from; id <= to; id++) s.add(EvalSupport.prices().get(id));
        return s;
    }

    private static String sha256(Path p) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.substring(0, 16) + "…";   // 报告里用短指纹，完整值见台账
    }}
