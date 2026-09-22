#!/usr/bin/env python3
"""未见集验收：机械项 + 泄漏 + 单轮自足性。

用法：
    python3 eval/check-unseen-batch.py eval/independent-intent-200.jsonl

检查项：
  1) JSONL 逐行可解析、字段齐全、枚举取值合法
  2) 七类配额（可用 --quota 覆盖，默认 40/30/35/25/15/25/30）
  3) 完全重复与近似重复（归一化后 2-gram Jaccard ≥ 0.85）
  4) 长度/书面腔/难例/最小对立对统计
  5) 泄漏：题面整句或 ≥8 字片段出现在项目 prompt → 污染；
     题面整句或 ≥5 字片段与"出题规范示例"重合 → 规格示例复用
  6) 单轮自足性：含指代/延续类标记的题面（COMPARE 类尤其要警惕——单轮无历史时它无法成立）
退出码：存在污染/配额不符 → 1。
"""
import argparse
import json
import re
import sys

PUNCT = r'[\s，。！？、,.!?；;：:"""'']'
ENUM_INTENT = {"PRODUCT_RECOMMENDATION", "CLARIFY_NEEDED", "PRODUCT_COMPARE",
               "ORDER_CONFIRM", "ORDER_CANCEL", "CHITCHAT", "OUT_OF_SCOPE"}
CATEGORIES = {"跑鞋", "手表", "耳机", "口红", None}
SCENARIOS = {"水泥路", "越野", "塑胶跑道", "健身房", "不固定", None}
GENDERS = {"male", "female", "unisex", None}
PD = {"cheaper", "expensive", None}

# 出题规范里我给过的示例（§3 括注 + §4 七条）——题目不得复用
SPEC_EXAMPLES = [
    "想找双下雨水里也不打滑的", "想添置点用得上的小电器", "这个还能再高级些吗", "就按这个来，给我安排",
    "先搁一搁，我再琢磨琢磨", "你平时也熬夜吗", "帮我看看我的积分还剩多少",
    "能便宜点卖吗", "给我打个折", "这款什么价位", "你们家靠谱吗", "太贵了，先算了",
    "想更便宜", "想更高级", "鞋子", "表", "耳机", "口红", "夜跑反光的", "能出声的",
]
CONTEXT_MARKERS = ["这款", "这双", "这个", "那个", "刚才", "继续", "它", "再看看其他", "换一个",
                   "别的", "再轻", "更轻", "大一号", "小一点", "颜色深", "中间那"]


def norm(s: str) -> str:
    return re.sub(PUNCT, '', s or "")


def frags(s: str, n: int):
    return {s[i:i + n] for i in range(max(0, len(s) - n + 1))}


def jaccard_2gram(a: str, b: str) -> float:
    A = {a[i:i + 2] for i in range(max(0, len(a) - 1))}
    B = {b[i:i + 2] for i in range(max(0, len(b) - 1))}
    if not A or not B:
        return 0.0
    return len(A & B) / len(A | B)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--prompt", default="src/main/resources/prompts/intent.txt")
    ap.add_argument("--goldens", default="eval/intent-golden.jsonl,eval/intent-golden-b2.jsonl,"
                                        "eval/heldout-intent.jsonl,eval/multiturn-intent.jsonl",
                    help="既有黄金集（V2 必须与它们也不重叠）")
    ap.add_argument("--quota", default="40,30,35,25,15,25,30",
                    help="按 ENUM_INTENT 顺序的配额")
    args = ap.parse_args()

    quota = dict(zip(["PRODUCT_RECOMMENDATION", "CLARIFY_NEEDED", "PRODUCT_COMPARE",
                      "ORDER_CONFIRM", "ORDER_CANCEL", "CHITCHAT", "OUT_OF_SCOPE"],
                     [int(x) for x in args.quota.split(",")]))
    prompt = norm(open(args.prompt, encoding="utf-8").read())

    rows, bad_json, schema_err = [], [], []
    for ln, line in enumerate(open(args.path, encoding="utf-8"), 1):
        if not line.strip():
            continue
        try:
            r = json.loads(line)
        except Exception as e:
            bad_json.append((ln, str(e)))
            continue
        rows.append(r)
        if r.get("intent") not in ENUM_INTENT:
            schema_err.append(f"L{ln} intent 非法: {r.get('intent')}")
        s = r.get("slots") or {}
        if s.get("category") not in CATEGORIES:
            schema_err.append(f"L{ln} category 非法: {s.get('category')}")
        if s.get("scenario") not in SCENARIOS:
            schema_err.append(f"L{ln} scenario 非法: {s.get('scenario')}")
        if s.get("gender") not in GENDERS:
            schema_err.append(f"L{ln} gender 非法: {s.get('gender')}")
        if s.get("priceDirection") not in PD:
            schema_err.append(f"L{ln} priceDirection 非法: {s.get('priceDirection')}")
        if s.get("budget") is not None and not isinstance(s.get("budget"), (int, float)):
            schema_err.append(f"L{ln} budget 非数字: {s.get('budget')}")

    print(f"总量: {len(rows)}  解析失败: {len(bad_json)}  字段错误: {len(schema_err)}")
    if bad_json:
        for ln, e in bad_json[:5]:
            print(f"  L{ln}: {e}")
    for e in schema_err[:10]:
        print("  ", e)

    # 配额
    from collections import Counter
    cnt = Counter(r["intent"] for r in rows)
    print("\n配额核对:")
    quota_ok = True
    for k, v in quota.items():
        got = cnt.get(k, 0)
        flag = "✓" if got == v else "✗"
        if got != v:
            quota_ok = False
        print(f"  {flag} {k}: {got}/{v}")

    # 重复
    texts = [norm(r.get("text", "")) for r in rows]
    dup, near = [], []
    seen = {}
    for i, t in enumerate(texts):
        if t in seen:
            dup.append((seen[t], i))
        else:
            seen[t] = i
    for i in range(len(texts)):
        for j in range(i + 1, len(texts)):
            if texts[i] and len(texts[i]) > 3 and jaccard_2gram(texts[i], texts[j]) >= 0.85:
                near.append((i, j))
    print(f"\n完全重复: {len(dup)}   近似重复(2-gram Jaccard≥0.85): {len(near)}")
    for i, j in near[:8]:
        print(f"  #{i} {rows[i]['text']}  ≈  #{j} {rows[j]['text']}")

    # 分布
    hard = sum(1 for r in rows if r.get("difficulty") == "hard")
    written = sum(1 for r in rows if r.get("style") == "written")
    lens = [len(norm(r["text"])) for r in rows]
    b1 = sum(1 for L in lens if 5 <= L <= 10)
    b2 = sum(1 for L in lens if 11 <= L <= 18)
    b3 = sum(1 for L in lens if 19 <= L <= 30)
    pairs = len({m.group(0) for r in rows
                 for m in [re.search(r'pair-\d+', r.get("note", ""))] if m})
    print(f"\n难例: {hard}   书面腔: {written}   长度 5-10/11-18/19-30: {b1}/{b2}/{b3}"
          f"   最小对立对: {pairs} 组")

    # 泄漏：与项目 prompt、既有黄金集、规范示例
    print("\n泄漏检查:")
    prior = {}   # 归一化文本 → 来源
    for gpath in [g for g in args.goldens.split(",") if g]:
        try:
            for line in open(gpath, encoding="utf-8"):
                if not line.strip():
                    continue
                rr = json.loads(line)
                if rr.get("text"):
                    prior[norm(rr["text"])] = gpath.split("/")[-1]
        except FileNotFoundError:
            pass
    prompt_hits, prior_hits, spec_hits = [], [], []
    for r in rows:
        t = norm(r.get("text", ""))
        if not t:
            continue
        if t in prompt or frags(t, 8) & frags(prompt, 8):
            prompt_hits.append(r["text"])
        src = prior.get(t)
        if not src and len(t) >= 6:
            for p, s in prior.items():
                if len(p) >= 6 and frags(t, 6) & frags(p, 6):
                    src = s
                    break
        if src:
            prior_hits.append((r["text"], src))
        for ex in SPEC_EXAMPLES:
            e = norm(ex)
            if t == e or (len(t) >= 5 and len(e) >= 5 and (frags(t, 5) & frags(e, 5))):
                spec_hits.append((r["text"], ex))
                break
    print(f"  与项目 prompt 重叠（整句或≥8字片段）: {len(prompt_hits)}")
    for t in prompt_hits[:10]:
        print(f"    ⚠ {t}")
    print(f"  与既有黄金集重叠（整句或≥6字片段）: {len(prior_hits)}")
    for t, s in prior_hits[:10]:
        print(f"    ⚠ {t}  ← {s}")
    print(f"  与出题规范示例重合（≥5字）: {len(spec_hits)}")
    for t, ex in spec_hits[:10]:
        print(f"    ⚠ {t}  ← 规范示例「{ex}」")

    # 单轮自足性（只提示，人工判断）
    ctx = [(r["intent"], r["text"]) for r in rows if any(m in r.get("text", "") for m in CONTEXT_MARKERS)]
    print(f"\n单轮自足性提示（含指代/延续标记，需人工确认）: {len(ctx)} 条")
    for it, t in ctx[:15]:
        print(f"    [{it}] {t}")

    ok = quota_ok and not bad_json and not schema_err and not prompt_hits and not prior_hits
    print("\n" + ("❌ 未通过（见上）" if not ok else "✅ 机械项与泄漏检查通过"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
