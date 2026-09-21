package com.smartore.voice.eval;

import com.smartore.voice.voice.AsrService;
import com.smartore.voice.voice.TtsService;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ASR 收敛参数网格搜索：flushGrace × settle 双指标（尾词丢失=CER、完成延迟）。
 * 生产默认值变更的唯一依据——不凭感觉调参。vad 固定 400（曾验证 300/350 句中截断）。
 */
@Tag("eval")
class EvalAsrSweepTest {

    private static TtsService tts;

    @BeforeAll
    static void guard() throws Exception {
        Assumptions.assumeTrue(EvalSupport.apiKeyPresent(), "需要 DASHSCOPE_API_KEY");
        tts = EvalSupport.buildTts();
    }

    @Test
    void 网格搜索() throws Exception {
        List<Map<String, Object>> queries = EvalSupport.loadGolden("eval/v2-speech-40.jsonl");
        // 10 句子集（含已知尾丢难例 S06/S10/S13）
        List<Map<String, Object>> subset = queries.stream()
                .filter(q -> List.of("S01", "S05", "S06", "S07", "S09", "S10", "S13", "S14", "S15", "S03")
                        .contains(q.get("id"))).toList();

        int[] graces = {400, 600};
        int[] settles = {500, 700, 900};
        StringBuilder report = new StringBuilder("## 网格：vad=400 固定，flushGrace × settle（ASR 内部收敛等待）\n\n"
                + "| grace | settle | CER均值 | 尾丢句数(CER>10%) | ASR完成延迟中位(ms) | 结论 |\n|--|--|--|--|--|--|\n");

        for (int grace : graces) {
            for (int settle : settles) {
                List<Double> cers = new ArrayList<>();
                List<Double> doneMs = new ArrayList<>();
                int tailLoss = 0;
                for (Map<String, Object> q : subset) {
                    String text = (String) q.get("query");
                    // TTS 合成（会话复用，生产路径）
                    ByteArrayOutputStream audio = new ByteArrayOutputStream();
                    tts.synthesize(Flowable.just(text)).blockingSubscribe(b -> {
                        byte[] a = new byte[b.remaining()];
                        b.get(a);
                        audio.write(a, 0, a.length);
                    });
                    byte[] pcm = audio.toByteArray();
                    // ASR（反射注入网格参数）
                    AsrService asr = EvalSupport.reflectSet(new AsrService(), Map.of(
                            "apiKey", System.getenv("DASHSCOPE_API_KEY"),
                            "model", System.getProperty("eval.asr.model", "qwen3.5-omni-flash-realtime"),
                            "vadSilenceMs", 400,
                            "flushGraceMs", grace, "settleMs", settle));
                    List<ByteBuffer> chunks = new ArrayList<>();
                    for (int i = 0; i < pcm.length; i += 3200) {
                        chunks.add(ByteBuffer.wrap(Arrays.copyOfRange(pcm, i, Math.min(i + 3200, pcm.length))));
                    }
                    long lastChunkAt = System.currentTimeMillis() + (long) chunks.size() * 100;   // paced 最后一块发出时刻
                    CompletableFuture<String> f = asr.recognize(
                            EvalSupport.paced(chunks, 100), (p, e) -> { });
                    String heard = f.get(30, TimeUnit.SECONDS);
                    doneMs.add((double) (System.currentTimeMillis() - lastChunkAt));
                    String ref = com.smartore.voice.voice.TtsTextNormalizer.normalize(text);
                    double cer = EvalMetrics.cer(ref, heard == null ? "" : heard);
                    cers.add(cer);
                    if (cer > 0.10) tailLoss++;
                }
                report.append(String.format("| %d | %d | %.1f%% | %d/%d | %.0f | %s |%n",
                        grace, settle, EvalMetrics.mean(cers) * 100, tailLoss, subset.size(),
                        EvalMetrics.median(doneMs),
                        tailLoss == 0 ? "✅零尾丢" : ""));
            }
        }
        report.append("""

                ## 口径
                - 尾丢句数 = CER>10% 的句子（1-2 个尾词丢失的典型特征）；ASR完成延迟 = 最后音频块（含垫尾）发出 → 定稿
                - 生产默认值按本表选择：零尾丢组合中取延迟最小者；全部有尾丢则维持现状并记录
                """);
        var path = EvalSupport.writeReport("asr-sweep-report.md", "ASR 收敛参数网格搜索", report.toString());
        System.out.println("[EVAL] ASR 网格报告已生成: " + path);
    }

}
