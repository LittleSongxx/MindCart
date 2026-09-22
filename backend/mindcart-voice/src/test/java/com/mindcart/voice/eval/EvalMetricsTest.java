package com.mindcart.voice.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 指标公式的手工验算：先自证正确，再用于评测。 */
class EvalMetricsTest {

    @Test
    // recall手工验算
    void case22() {
        // 检索 [A,B,C,D,E]，相关 {A,C,Z} → 前5命中 {A,C} → 2/3
        double r = EvalMetrics.recallAtK(
                List.of(1L, 2L, 3L, 4L, 5L), Set.of(1L, 3L, 99L), 5);
        assertEquals(2.0 / 3, r, 1e-9);
    }

    @Test
    // recall_k截断生效
    void case23() {
        // 相关 {D}，前 3 里没有 → 0；前 5 里有 → 1
        assertEquals(0.0, EvalMetrics.recallAtK(List.of(1L, 2L, 3L, 4L), Set.of(4L), 3));
        assertEquals(1.0, EvalMetrics.recallAtK(List.of(1L, 2L, 3L, 4L), Set.of(4L), 4));
    }

    @Test
    // recall_相关集空定义为1
    void case24() {
        assertEquals(1.0, EvalMetrics.recallAtK(List.of(1L), Set.of(), 5));
    }

    @Test
    // mrr手工验算
    void case25() {
        // 第一个相关结果排第 3 → 1/3
        assertEquals(1.0 / 3, EvalMetrics.mrr(List.of(7L, 8L, 9L, 10L), Set.of(9L, 10L)), 1e-9);
        assertEquals(1.0, EvalMetrics.mrr(List.of(5L, 6L), Set.of(5L)), 1e-9);
        assertEquals(0.0, EvalMetrics.mrr(List.of(5L, 6L), Set.of(99L)), 1e-9);
    }

    @Test
    // ndcg_理想排列为1
    void case26() {
        Set<Long> rel = Set.of(1L, 2L);
        assertEquals(1.0, EvalMetrics.ndcgAtK(List.of(1L, 2L, 3L), rel, 3), 1e-9);
    }

    @Test
    // ndcg_手工验算_两个相关都在但顺序靠后
    void case27() {
        // 检索 [X,1,2]：DCG = 1/log2(3) + 1/log2(4)；iDCG = 1/log2(2)+1/log2(3)
        double dcg = 1.0 / (Math.log(3) / Math.log(2)) + 1.0 / (Math.log(4) / Math.log(2));
        double idcg = 1.0 / (Math.log(2) / Math.log(2)) + 1.0 / (Math.log(3) / Math.log(2));
        assertEquals(dcg / idcg,
                EvalMetrics.ndcgAtK(List.of(99L, 1L, 2L), Set.of(1L, 2L), 3), 1e-9);
    }

    @Test
    // ndcg_无相关为0
    void case28() {
        assertEquals(0.0, EvalMetrics.ndcgAtK(List.of(1L, 2L), Set.of(), 3));
    }

    @Test
    // cer_完全一致为0_标点差异不算错
    void case29() {
        assertEquals(0.0, EvalMetrics.cer("好的，稍等。", "好的稍等"), 1e-9);
    }

    @Test
    // cer_手工验算_替换一字
    void case30() {
        // 参考 5 字，识别替换 1 字（鸡→七）→ 1/5
        assertEquals(0.2, EvalMetrics.cer("鸡哥给你挑", "七哥给你挑"), 1e-9);
    }

    @Test
    // cer_删除与插入都计费
    void case31() {
        assertEquals(1.0 / 3, EvalMetrics.cer("一二三", "一二"), 1e-9);     // 删 1
        assertEquals(1.0 / 3, EvalMetrics.cer("一二三", "一二三四"), 1e-9); // 插 1
    }

    @Test
    // 中位数与分位数
    void case32() {
        List<Double> xs = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        assertEquals(3.0, EvalMetrics.median(xs), 1e-9);
        assertEquals(1.0, EvalMetrics.percentile(xs, 0.0), 1e-9);
        assertEquals(3.0, EvalMetrics.percentile(xs, 0.5), 1e-9);
        assertEquals(5.0, EvalMetrics.percentile(xs, 1.0), 1e-9);
        // 偶数个：中位取中间两数均值
        assertEquals(2.5, EvalMetrics.median(List.of(1.0, 2.0, 3.0, 4.0)), 1e-9);
    }

    @Test
    // bootstrapCI：区间包含真均值且宽度合理（固定种子可复现）
    void caseBootstrapCi() {
        List<Double> xs = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) xs.add(0.8 + 0.4 * (i % 5) / 4.0);   // [0.8,1.2] 均值1.0
        double[] ci = EvalMetrics.bootstrapCI(xs);
        assertTrue(ci[0] <= 1.0 && 1.0 <= ci[1]);
        assertTrue(ci[1] - ci[0] < 0.2, "区间过宽: " + java.util.Arrays.toString(ci));
        // 可复现：同输入同区间
        double[] ci2 = EvalMetrics.bootstrapCI(xs);
        assertArrayEquals(ci, ci2, 1e-12);
    }

    @Test
    // pairedBootstrapCI：全正差异 → 区间不含 0；含正负抵消 → 区间跨 0
    void casePairedBootstrap() {
        // 每条都 +0.1：区间应整体在 0 右侧
        List<Double> a = List.of(0.5, 0.6, 0.4, 0.7, 0.55, 0.65, 0.45, 0.75,
                0.5, 0.6, 0.4, 0.7, 0.55, 0.65, 0.45, 0.75, 0.5, 0.6, 0.4, 0.7);
        List<Double> b = a.stream().map(x -> x + 0.1).toList();
        double[] ci = EvalMetrics.pairedBootstrapCI(a, b);
        assertTrue(ci[0] > 0, "全正差异区间应不含0: " + java.util.Arrays.toString(ci));
        assertEquals(0.1, EvalMetrics.meanDiff(a, b), 1e-9);

        // 一正一负各半 → 区间跨 0（消融必须诚实报告这种情形）
        List<Double> c = List.of(0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0);
        List<Double> d = List.of(1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0);
        double[] ci2 = EvalMetrics.pairedBootstrapCI(c, d);
        assertTrue(ci2[0] <= 0 && ci2[1] >= 0, "正负抵消应跨0: " + java.util.Arrays.toString(ci2));
    }
}
