package com.mindcart.voice.eval;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 话术价格忠实度判定的手工验算：既不能把真实近似说法记成失实（红条误报），
 * 也不能把编造价格放过（指标失去意义）——两个方向都要有回归保护。
 */
class SpeechPriceCheckTest {

    private static final Set<Double> WATCH = Set.of(899.0, 1680.0, 2280.0, 3880.0);

    @Test
    // 精确命中：阿拉伯数字与中文读法都算对（中文先经 CnNum 归一化）
    void 精确命中通过() {
        assertTrue(SpeechPriceCheck.check("这款机械表八百九十九元，很值", WATCH).ok());
        assertTrue(SpeechPriceCheck.check("价格是899元，还带保修", WATCH).ok());
        assertTrue(SpeechPriceCheck.check("两千二百八十块钱拿下", WATCH).ok());
    }

    @Test
    // 无价格话术：不编造即通过
    void 无价格提及通过() {
        assertTrue(SpeechPriceCheck.check("这几款都挺适合日常佩戴的，你更看重哪点？", WATCH).ok());
    }

    @Test
    // 近似说法相符通过：上界型（不到九百 → 899）、下界型（一千六出头 → 1680）、约数型（两千三左右 → 2280）
    void 近似说法相符通过() {
        assertTrue(SpeechPriceCheck.check("这款不到九百元，耐造又省心", WATCH).ok(),
                "不到九百元 对 899 是真陈述");
        assertTrue(SpeechPriceCheck.check("光动能这款一千六出头，不用换电池", WATCH).ok(),
                "一千六出头 对 1680 是真陈述");
        assertTrue(SpeechPriceCheck.check("预算两千三左右的话可以看这款", WATCH).ok(),
                "两千三左右 对 2280 接近");
    }

    @Test
    // 编造价格必须抓住：无近似词且不命中库内价格
    void 编造价格被抓住() {
        SpeechPriceCheck.Verdict v1 = SpeechPriceCheck.check("这款手表只要1288元", WATCH);
        assertFalse(v1.ok(), "1288 不是库内价格，且无近似词 → 编造");
        SpeechPriceCheck.Verdict v2 = SpeechPriceCheck.check("原价5000元，现在3800元", WATCH);
        assertFalse(v2.ok(), "5000/3800 均不在库内且无近似词 → 失实");
    }

    @Test
    // 预算复述不是商品报价主张：助手回述用户预算（"预算800""5000封顶""500以内挑耳机"）不计失实
    void 预算复述不计为商品报价() {
        assertTrue(SpeechPriceCheck.check("预算800跑水泥路的话，这双899很合适", WATCH).ok()
                || SpeechPriceCheck.check("预算800跑水泥路的话", WATCH).ok());
        assertTrue(SpeechPriceCheck.check("你预算1500想找机械表，可以看这块1680的", WATCH).ok()
                || SpeechPriceCheck.check("你预算1500想找机械表", WATCH).ok());
        assertTrue(SpeechPriceCheck.check("500以内挑耳机的话，这款299合适", Set.of(299.0)).ok()
                || SpeechPriceCheck.check("500以内挑耳机的话", Set.of(299.0)).ok());
        assertTrue(SpeechPriceCheck.check("200米防水，5000封顶的话看这块4580的", WATCH).ok()
                || SpeechPriceCheck.check("5000封顶的话", WATCH).ok());
    }

    @Test
    // 量级步长近似："三百多"对 399、"一千六出头"对 1680 都成立；不成立的仍失实
    void 量级步长近似区间() {
        assertTrue(SpeechPriceCheck.check("入门石英女表三百多，钢带", Set.of(399.0)).ok(), "三百多 → [300,400) 含 399");
        assertTrue(SpeechPriceCheck.check("光动能这块一千六出头", WATCH).ok(), "一千六出头 → [1600,1700) 含 1680");
        assertFalse(SpeechPriceCheck.check("这款五千出头就能拿下", WATCH).ok(), "[5000,6000) 无库内价 → 失实");
    }

    @Test
    // 非价格数字不得误伤：公里数/尺码/年款/规格
    void 非价格数字不计为价格提及() {
        assertTrue(SpeechPriceCheck.check("水泥路每天跑5公里，缓震优先", WATCH).ok());
        assertTrue(SpeechPriceCheck.check("200米防水，2026年新款", WATCH).ok());
        assertTrue(SpeechPriceCheck.check("80小时动力储存，42码", WATCH).ok());
    }

    @Test
    // 实测误报模式不得误伤（含相对量、预算区间、型号数字三类）
    void 实测误报模式不得误伤() {
        assertTrue(SpeechPriceCheck.check("这几款都在你5000预算里。", WATCH).ok(), "预算在后置窗口");
        assertTrue(SpeechPriceCheck.check("想要3000左右的机械表送人的话，这块3880合适", WATCH).ok(), "需求复述+近似");
        assertTrue(SpeechPriceCheck.check("跑步机快走用，800以内这两款都行", Set.of(599.0, 699.0)).ok(), "以内+指示词");
        assertTrue(SpeechPriceCheck.check("轻量跑鞋，贵100但脚感更好，899这双", Set.of(899.0)).ok(), "贵100 是相对量");
        assertTrue(SpeechPriceCheck.check("好嘞，500以内听歌这两款，299的就行", Set.of(299.0)).ok(), "以内+无商品指代=复述预算");
        assertTrue(SpeechPriceCheck.check("卡西欧GA-2100不到九百，耐造又省心", WATCH).ok(), "型号数字 GA-2100 不是价格");
        assertTrue(SpeechPriceCheck.check("Pegasus 40 这双899，日常慢跑够用", Set.of(899.0)).ok(), "型号数字 40 不是价格");
        assertTrue(SpeechPriceCheck.check("两百多米防水，800多块拿下", WATCH).ok(), "米防水是规格；八百多对 899 成立");
        assertTrue(SpeechPriceCheck.check("200米防水，899元", WATCH).ok(), "规格+真价混排");
    }

    @Test
    // 上界/区间型：只要库内存在 ≤ 所述值的价格即成立——"599不到八百"是真陈述（实测误报过的形态）
    void 上界区间型按可满足性判定() {
        assertTrue(SpeechPriceCheck.check("入门款599不到八百，性价比不错", Set.of(599.0)).ok(), "599 < 800 成立");
        assertTrue(SpeechPriceCheck.check("八百以内的跑鞋有这两双", Set.of(599.0, 699.0)).ok(), "区间可满足");
        assertFalse(SpeechPriceCheck.check("八百以内的跑鞋有这两双", Set.of(1180.0, 899.0)).ok(), "库内无 ≤800 的价 → 失实");
        assertFalse(SpeechPriceCheck.check("不到五百就能拿下", Set.of(899.0)).ok(), "库内无 ≤500 的价 → 失实");
    }

    @Test
    // 相对量（含货币后缀）不误伤；但"数字+元"后接相对量词不得借此放过编造
    void 相对量词的方向性() {
        assertTrue(SpeechPriceCheck.check("比第一款省下200块钱", Set.of(599.0)).ok(), "省下200块钱 = 差价");
        assertTrue(SpeechPriceCheck.check("轻量款贵100，脚感更好，899这双", Set.of(899.0)).ok(), "贵100 = 差价");
        assertFalse(SpeechPriceCheck.check("1288元，贵是贵了点但值", Set.of(899.0)).ok(), "带后缀的价格主张照常校验");
    }

    @Test
    // 型号识别不能把真价格滤掉：带货币后缀的裸数字价格仍受检
    void 型号过滤不放过裸价() {
        assertFalse(SpeechPriceCheck.check("这双鞋1234元，很划算", Set.of(899.0)).ok(), "1234 元是价格主张，不是型号");
        assertFalse(SpeechPriceCheck.check("只要1288元就能拿下", Set.of(899.0)).ok(), "中文语境后缀元不得被当作型号字母");
    }

    @Test
    // 上界型口径已统一为"库内存在 ≤ 所述值的价即成立"（真陈述不记失实）；
    // 边界落在"不可满足"侧：库内没有 ≤ 该值的商品才算失实
    void 近似词口径边界() {
        assertTrue(SpeechPriceCheck.check("这款不到三千元就能拿下", Set.of(899.0)).ok(),
                "899 < 3000，'不到三千'是真陈述");
        assertFalse(SpeechPriceCheck.check("这款不到五百元就能拿下", Set.of(899.0)).ok(),
                "库内无 ≤500 的价 → 该说法对任何在售商品都不成立 → 失实");
    }

    @Test
    // 多条价格混排：命中与近似混用时逐条校验
    void 混合提及逐条校验() {
        assertTrue(SpeechPriceCheck.check("入门款八百九十九元，高端款两千三左右", WATCH).ok());
        assertFalse(SpeechPriceCheck.check("入门款八百九十九元，还有款4599元", WATCH).ok());
    }
}
