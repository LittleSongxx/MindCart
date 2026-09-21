package com.smartore.voice.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评测指标工具：recall@k / mrr / ndcg@k / cer。
 * 口径全部在 eval/README.md 定义；本类只做纯计算，公式正确性由 EvalMetricsTest 用
 * 手工可算的小样本验证（评测工具先自证，再用于评测）。
 */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /** Recall@K = |检索结果前K ∩ 相关集| / |相关集|。相关集为空时定义为 1（无 recall 义务）。 */
    public static double recallAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (relevant.isEmpty()) return 1.0;
        Set<Long> topK = new HashSet<>(retrieved.subList(0, Math.min(k, retrieved.size())));
        topK.retainAll(relevant);
        return (double) topK.size() / relevant.size();
    }

    /** MRR = 1 / 第一个相关结果的排名；无相关结果命中时为 0。 */
    public static double mrr(List<Long> retrieved, Set<Long> relevant) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    /** nDCG@K：二元相关性（命中 relevant 记 1）。DCG=Σ rel_i/log2(i+1)，iDCG 取理想排列。 */
    public static double ndcgAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        List<Long> topK = retrieved.subList(0, Math.min(k, retrieved.size()));
        double dcg = 0;
        for (int i = 0; i < topK.size(); i++) {
            if (relevant.contains(topK.get(i))) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        int idealHits = Math.min(relevant.size(), k);
        if (idealHits == 0) return 0.0;
        double idcg = 0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return dcg / idcg;
    }

    /** CER（字符错误率）= 编辑距离(参考文本, 识别文本) / 参考文本长度。 */
    public static double cer(String reference, String hypothesis) {
        if (reference == null || reference.isEmpty()) return 0.0;
        String ref = normalize(reference);
        String hyp = normalize(hypothesis);
        int n = ref.length(), m = hyp.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = ref.charAt(i - 1) == hyp.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return (double) dp[n][m] / n;
    }

    /** 口径：去掉标点与空白后比较——标点差异不算识别错误（TTS/ASR 的标点行为不稳定）。 */
    static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\p{P}\\s]", "");
    }

    /** 均值（6 个评测器此前各有一份 avg/mean 实现，统一到此处）。 */
    public static double mean(List<Double> xs) {
        return xs == null || xs.isEmpty() ? Double.NaN
                : xs.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    public static double median(List<Double> xs) {
        List<Double> sorted = new ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        if (sorted.isEmpty()) return Double.NaN;
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /** bootstrap 95% 置信区间（均值，重抽样 1000 次，固定种子可复现）。返回 [lo, hi]。 */
    public static double[] bootstrapCI(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        java.util.Random rng = new java.util.Random(42);
        int n = xs.size();
        double[] means = new double[1000];
        double[] arr = new double[n];
        for (int i = 0; i < n; i++) arr[i] = xs.get(i);
        for (int b = 0; b < 1000; b++) {
            double s = 0;
            for (int i = 0; i < n; i++) s += arr[rng.nextInt(n)];
            means[b] = s / n;
        }
        java.util.Arrays.sort(means);
        return new double[]{means[25], means[974]};   // 2.5% / 97.5%
    }

    /** P 分位数（线性插值），p∈(0,1]。 */
    public static double percentile(List<Double> xs, double p) {
        List<Double> sorted = new ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        if (sorted.isEmpty()) return Double.NaN;
        double idx = p * (sorted.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(lo + 1, sorted.size() - 1);
        return sorted.get(lo) + (idx - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    /**
     * 配对 bootstrap：对同一批样本上两组逐条配对的差异 (b_i - a_i) 重抽样，
     * 返回均差 95% CI [lo, hi]（固定种子可复现）。
     * <p>
     * 消融对照必须用配对口径——非配对 CI 把"query 难度差异"混进了区间宽度，
     * 小样本下会得出"不显著"的假阴性（A5 三臂在 45 条同 query 上有强配对结构）。
     * CI 跨 0 = 该组件在小样本上的增益不可分辨，结论不得写成"胜出"。
     */
    public static double[] pairedBootstrapCI(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || a.size() != b.size()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        int n = a.size();
        double[] diff = new double[n];
        for (int i = 0; i < n; i++) diff[i] = b.get(i) - a.get(i);
        java.util.Random rng = new java.util.Random(42);
        double[] means = new double[1000];
        for (int bIdx = 0; bIdx < 1000; bIdx++) {
            double s = 0;
            for (int i = 0; i < n; i++) s += diff[rng.nextInt(n)];
            means[bIdx] = s / n;
        }
        java.util.Arrays.sort(means);
        return new double[]{means[25], means[974]};
    }

    /** 配对均差（b - a）的点估计。 */
    public static double meanDiff(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || a.size() != b.size()) return Double.NaN;
        double s = 0;
        for (int i = 0; i < a.size(); i++) s += b.get(i) - a.get(i);
        return s / a.size();
    }
}
