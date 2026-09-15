package com.smartore.ai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：BM25 关键词通道 + RRF 排名融合。
 *
 * 为什么需要关键词通道（真实检索缺陷，非设计装饰）：
 * 商品语料里大量「编号/型号/数字」类 token（SP202606260019、M3、ISBN、1000mAh），
 * 语义向量对这类字面精确查询会把「同类商品」排在目标前面；BM25 对字面命中敏感，
 * 恰好补上这块。两路各自排名后用 RRF（Σ 1/(k+rank)，只融名次不融不可比的分数）合并。
 *
 * BM25 在内存中计算：切片量为演示级（数百），全量打分一次遍历，
 * 与稠密通道同数量级；上量后换成倒排索引/全文检索的触发条件已写在架构文档。
 */
@Service
public class HybridRetrievalService {

    /** RRF 平滑常数：常见起点 60，控制头部名次差异的影响（面试点：不是业务定律，是可调起点） */
    public static final int RRF_K = 60;

    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;

    /**
     * 中英混合分词：ASCII 字母数字连串保留为整 token（编号/型号不断开），
     * 中文按 bigram（单个汉字 token 噪声大，bigram 兼顾准确与召回）。
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase();
        StringBuilder ascii = new StringBuilder();
        List<Integer> cjk = new ArrayList<>();
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            if (isAsciiAlnum(cp)) {
                if (!cjk.isEmpty()) {
                    flushCjk(cjk, tokens);
                    cjk.clear();
                }
                ascii.appendCodePoint(cp);
                i += Character.charCount(cp);
            } else if (isCjk(cp)) {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString());
                    ascii.setLength(0);
                }
                cjk.add(cp);
                i += Character.charCount(cp);
            } else {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString());
                    ascii.setLength(0);
                }
                if (!cjk.isEmpty()) {
                    flushCjk(cjk, tokens);
                    cjk.clear();
                }
                i += Character.charCount(cp);
            }
        }
        if (ascii.length() > 0) {
            tokens.add(ascii.toString());
        }
        if (!cjk.isEmpty()) {
            flushCjk(cjk, tokens);
        }
        return tokens;
    }

    private void flushCjk(List<Integer> cjk, List<String> tokens) {
        for (int i = 0; i < cjk.size(); i++) {
            if (i + 1 < cjk.size()) {
                tokens.add(new String(Character.toChars(cjk.get(i))) + new String(Character.toChars(cjk.get(i + 1))));
            } else {
                tokens.add(new String(Character.toChars(cjk.get(i))));
            }
        }
    }

    private boolean isAsciiAlnum(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9');
    }

    private boolean isCjk(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    /**
     * Okapi BM25 打分。docs: id → 文档文本。返回按分数降序的 id 列表。
     */
    public <K> List<K> bm25Rank(String query, Map<K, String> docs) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || docs.isEmpty()) {
            return List.of();
        }
        int n = docs.size();
        Map<K, List<String>> docTokens = new HashMap<>();
        Map<K, Integer> docLength = new HashMap<>();
        double avgLength = 0;
        for (Map.Entry<K, String> entry : docs.entrySet()) {
            List<String> tokens = tokenize(entry.getValue());
            docTokens.put(entry.getKey(), tokens);
            docLength.put(entry.getKey(), tokens.size());
            avgLength += tokens.size();
        }
        avgLength /= n;

        // 文档频率 → 逆文档频率
        Map<String, Integer> df = new HashMap<>();
        for (List<String> tokens : docTokens.values()) {
            for (String unique : new java.util.HashSet<>(tokens)) {
                df.merge(unique, 1, Integer::sum);
            }
        }

        Map<K, Double> scores = new HashMap<>();
        for (Map.Entry<K, List<String>> entry : docTokens.entrySet()) {
            K id = entry.getKey();
            Map<String, Integer> tf = new HashMap<>();
            for (String token : entry.getValue()) {
                tf.merge(token, 1, Integer::sum);
            }
            double score = 0;
            int length = docLength.get(id);
            double norm = BM25_K1 * (1 - BM25_B + BM25_B * (length / Math.max(1, avgLength)));
            for (String token : queryTokens) {
                Integer freq = tf.get(token);
                if (freq == null) {
                    continue;
                }
                int docFreq = df.getOrDefault(token, 0);
                double idf = Math.log(1 + (n - docFreq + 0.5) / (docFreq + 0.5));
                score += idf * (freq * (BM25_K1 + 1)) / (freq + norm);
            }
            if (score > 0) {
                scores.put(id, score);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<K, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * RRF 融合：只融合名次（两路分数量纲不可比），score = Σ 1/(RRF_K + rank)。
     * rank 从 1 开始；未出现在某路的候选不贡献该项。
     */
    public <K> List<K> rrfFuse(List<K> denseRank, List<K> keywordRank) {
        return rrfFuse(List.of(denseRank, keywordRank));
    }

    public <K> List<K> rrfFuse(List<List<K>> rankings) {
        Map<K, Double> fused = new LinkedHashMap<>();
        for (List<K> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                fused.merge(ranking.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<K, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }
}
