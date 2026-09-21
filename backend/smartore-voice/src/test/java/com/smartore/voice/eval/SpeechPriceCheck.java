package com.smartore.voice.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 话术价格忠实度判定（纯函数，无网络——独立于 HTTP 评测器可单测）。
 * <p>
 * 判定"话术里报的价格是否与商品库一致"，两种通过路径：
 * <ol>
 *   <li>精确命中库内价格；</li>
 *   <li>近似说法且与库内某价相符——"不到九百"（对 899 成立）、"一千六出头"（对 1680 成立）是
 *       自然的语音表达，不属编造。</li>
 * </ol>
 * 无近似词又不命中 = 编造（如凭空报 1288）。中文数字读法先经 CnNum 归一化后同检（"一百四"→140）。
 * <p>
 * 为什么不只做严格相等：严格等式会把"不到九百"这类**真陈述**记成失实（曾导致评测红条），
 * 反而弱化了指标的可信度；而放宽"任意数字都算对"又会漏掉编造。近似词 + 数值一致性是两个约束的最小交集。
 * <p>
 * 已知局限（明示，不隐藏）：价格提及的识别有两种路径——① 带货币后缀（元/块钱/块）；
 * ② 数字处于合理价格区间（50~20000）且邻接价格语义词（价/钱/预算/花/多/左右/出头/不到…）。
 * 这仍可能漏掉极端口语（"三张"指三百元），也可能把"两百公里"这类相邻词误伤——两端风险都已用区间与词表压到最低。
 */
final class SpeechPriceCheck {

    /**
     * 价格提及：捕获前后窗口。
     * 窄窗（4 字）用于语义词/近似词/相对量词判定（必须紧邻）；
     * 宽窗（12 字）用于预算复述与商品指代判定（"800以内的跑鞋…"里"跑鞋"常在 4 字之外）。
     */
    private static final Pattern PRICE_UNIT = Pattern.compile("(.{0,12}?)(\\d{2,6})(?:元|块钱|块)(.{0,12})");
    private static final Pattern PRICE_CONTEXT = Pattern.compile("(.{0,12}?)(\\d{2,6})(.{0,12})");
    private static final int NARROW = 4;
    /** 价格语义词：出现其一，且数值落在 [MIN, MAX]，才认为该数字是价格提及。 */
    private static final List<String> PRICE_WORDS = List.of(
            "价", "钱", "预算", "花", "省", "不到", "以内", "以下", "以上", "出头",
            "左右", "上下", "超过", "高于", "低于", "多", "便宜", "贵", "块", "元");
    /** 预算/需求复述：用户自述的预算或诉求被助手回述（"预算800""5000封顶""想要3000左右"）→ 不是商品报价主张。 */
    private static final List<String> BUDGET_ECHO = List.of("预算", "封顶", "上限", "需求", "想要", "想找");
    /** 相对量词：紧邻数字时表示**差价/省下的钱**（"贵100""省300"），不是绝对报价 → 跳过（无货币后缀时）。 */
    private static final List<String> RELATIVE_MARKERS = List.of("贵", "省", "便宜", "多花", "少花", "差");
    /** 商品指代：判断"X以内/以下"是在约束商品集合（报价主张）还是在复述预算（"800以内完全够"）。 */
    private static final List<String> PRODUCT_REFS = List.of(
            "款", "双", "块", "支", "个", "只", "这几", "那几", "跑鞋", "手表", "耳机", "口红", "商品", "货");
    /** 规格度量单位：数字紧随其后时是参数（"200米防水""80小时续航""42码"），不是价格。 */
    private static final List<String> SPEC_UNITS = List.of(
            "米", "公里", "千米", "克", "千克", "毫升", "升", "寸", "码", "岁", "年", "月", "天",
            "小时", "分钟", "秒", "次", "人", "瓦", "毫安", "赫兹", "号", "档", "级", "倍", "GB", "G");
    private static final double MIN_PRICE = 50, MAX_PRICE = 20000;
    /** 下界型近似：真实价 ≥ 所述值（"八百多""一千六出头"）。 */
    private static final List<String> APPROX_LOWER = List.of("多", "出头", "以上", "超过", "高于", "多于");
    /** 上界/区间型（"不到九百""800以内""599不到八百"）：只要库内存在 ≤ 所述值的价格，该说法即成立。 */
    private static final List<String> APPROX_UPPER = List.of("不到", "不超过", "低于", "以内", "以下", "内");
    /** 约数型近似：真实价与所述值接近（"一千六左右""约两千"）。 */
    private static final List<String> APPROX_NEAR = List.of("左右", "上下", "约", "近", "差不多");

    record Verdict(boolean ok, List<String> violations) {
    }

    private SpeechPriceCheck() {
    }

    /** 校验一条话术：所有价格提及都必须与库内价格一致（精确或近似相符）。 */
    static Verdict check(String speech, Set<Double> legalPrices) {
        String text = CnNum.toDigits(speech == null ? "" : speech);
        List<String> violations = new ArrayList<>();
        // 位置去重：同一处提及可能同时命中两条规则（如"899元"带后缀也邻近"元"）
        Set<Integer> seen = new java.util.HashSet<>();
        collect(text, PRICE_UNIT, seen, violations, legalPrices);
        collect(text, PRICE_CONTEXT, seen, violations, legalPrices);
        return new Verdict(violations.isEmpty(), violations);
    }

    private static void collect(String text, Pattern pattern, Set<Integer> seen,
                                List<String> violations, Set<Double> legalPrices) {
        Matcher m = pattern.matcher(text);
        int from = 0;
        while (from <= text.length()) {
            m.region(from, text.length());
            if (!m.find()) break;
            int digitStart = m.start(2), digitEnd = m.end(2);
            from = digitEnd;      // 从数字末尾继续扫描：宽窗口不得吞掉后续数字（否则"…899元，还有4599元"漏检）
            if (!seen.add(digitStart)) continue;              // 该数字已判定过
            String before = m.group(1) == null ? "" : m.group(1);
            String after = m.group(3) == null ? "" : m.group(3);
            String nearBefore = tail(before, NARROW);
            String nearAfter = head(after, NARROW);
            double p = Double.parseDouble(m.group(2));
            if (p < MIN_PRICE || p > MAX_PRICE) continue;      // 公里数/尺码/年款等
            if (isModelNumber(text, digitStart, digitEnd)) continue;   // 型号里的数字（"GA-2100""Pegasus 40"）
            boolean hasUnit = pattern == PRICE_UNIT;
            if (!hasUnit && hasSpecUnit(text, digitEnd)) continue;      // "200米防水""80小时续航"：参数不是价格
            boolean hasContextWord = PRICE_WORDS.stream()
                    .anyMatch(w -> nearBefore.contains(w) || nearAfter.contains(w));
            if (!hasUnit && !hasContextWord) continue;         // 非价格提及（无后缀又无语义词）
            if (hasRelativeMarker(before, after, hasUnit)) continue;   // "贵100""省下200块钱"：相对量，非绝对报价
            if (isBudgetEcho(before, after, hasUnit)) continue;  // 预算/需求复述不是商品报价主张
            if (legalPrices.contains(p)) continue;
            boolean approxOk =
                    approxMatch(p, legalPrices, APPROX_LOWER, nearBefore, nearAfter,
                            (q, v) -> q >= v && q < v + roundStep(v))                 // 下界型："三百多"→[300,400)
                            || approxMatch(p, legalPrices, APPROX_UPPER, nearBefore, nearAfter,
                            (q, v) -> q <= v)                                        // 上界/区间型："不到九百"/"800以内"
                            || approxMatch(p, legalPrices, APPROX_NEAR, nearBefore, nearAfter,
                            (q, v) -> Math.abs(q - v) <= v * 0.1);                    // 约数型："一千六左右"
            if (!approxOk) {
                violations.add("话术报 " + p + "（上下文「" + nearBefore + m.group(2) + nearAfter + "」）");
            }
        }
    }

    private static String tail(String s, int n) {
        return s.length() > n ? s.substring(s.length() - n) : s;
    }

    private static String head(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /**
     * 型号数字识别：数字左侧（跳过 -_./空格）紧邻**拉丁字母**，或右侧紧邻拉丁字母 → 属型号（"GA-2100""iPhone 15"），
     * 不是价格。注意必须限定拉丁字母：中文也是"字母"（Character.isLetter('元')=true），
     * 用 isLetter 会把"1288元"的后缀误判成型号前缀而放过编造价格。
     */
    private static boolean isModelNumber(String text, int digitStart, int digitEnd) {
        for (int i = digitStart - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '-' || c == '_' || c == '.' || c == '/') continue;
            if (isLatinLetter(c)) return true;
            break;
        }
        for (int i = digitEnd; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '.') continue;
            return isLatinLetter(c);
        }
        return false;
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 数字后紧随规格度量单位 → 参数值（"200米""80小时""200多米"），不是价格主张。 */
    private static boolean hasSpecUnit(String text, int digitEnd) {
        String rest = text.substring(digitEnd);
        // "两百多米防水"：口语的"多"夹在数字与单位之间，先剥掉再判单位（"八百多块"剥后是"块"→ 仍是价格）
        while (!rest.isEmpty() && "多余来几".indexOf(rest.charAt(0)) >= 0) {
            rest = rest.substring(1);
        }
        String r = rest;
        return SPEC_UNITS.stream().anyMatch(r::startsWith);
    }

    /**
     * 预算/需求复述识别：
     * ① 窗口出现"预算/封顶/上限/想要"（两种语序）；
     * ② "X以内/以下"但窗口内没有商品指代（"800以内完全够""500以内听歌"= 复述用户预算，
     *    而"800以内的跑鞋有这几双"含商品指代 → 仍是报价主张，照常校验）。
     */
    private static boolean isBudgetEcho(String before, String after, boolean hasUnit) {
        if (BUDGET_ECHO.stream().anyMatch(w -> before.contains(w) || after.contains(w))) return true;
        boolean rangeSuffix = after.contains("以内") || after.contains("以下");
        if (!rangeSuffix) return false;
        boolean productRef = PRODUCT_REFS.stream().anyMatch(w -> before.contains(w) || after.contains(w));
        return !productRef && !hasUnit;
    }

    /**
     * 相对量："贵100""省下200块钱"——数字与相对量词紧邻（≤2 字）。
     * 方向性规则：量词在数字**之前**→ 一律视为相对量（"省下200块钱"含货币后缀也是差价）；
     * 量词在数字**之后**且带货币后缀（"899元，贵是贵了点"）→ 仍是价格主张，照常校验，不给编造留后门。
     */
    private static boolean hasRelativeMarker(String before, String after, boolean hasUnit) {
        String b = tail(before, 2);
        if (RELATIVE_MARKERS.stream().anyMatch(b::contains)) return true;
        if (hasUnit) return false;
        String a = head(after, 2);
        return RELATIVE_MARKERS.stream().anyMatch(a::contains);
    }

    /** 口语近似的最小量级步长："八百多"→[800,900)、"一千六出头"→[1600,1700)、"三千多"→[3000,4000)。 */
    private static double roundStep(double v) {
        if (v >= 1000 && v % 1000 == 0) return 1000;
        if (v % 100 == 0) return 100;
        if (v % 10 == 0) return 10;
        return 1;
    }

    /** 近似说法校验：句中出现该类近似词且库内存在满足该类型约束的价格才算相符；无近似词返回 false。 */
    private static boolean approxMatch(double stated, Set<Double> legalPrices, List<String> markers,
                                       String before, String after, BiPredicate<Double, Double> consistent) {
        boolean marked = markers.stream().anyMatch(w -> before.contains(w) || after.contains(w));
        if (!marked) return false;
        return legalPrices.stream().anyMatch(q -> consistent.test(q, stated));
    }
}
