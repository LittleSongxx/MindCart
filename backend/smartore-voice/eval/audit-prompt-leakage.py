#!/usr/bin/env python3
"""prompt 泄漏审计：检查评测集题面是否出现在意图 prompt 里。

用法（仓库根目录执行）：
    python3 eval/audit-prompt-leakage.py                 # 审计 intent.txt 与 eval/*.jsonl
    python3 eval/audit-prompt-leakage.py --baseline <file>  # 区分"本轮引入"与"历史已有"

判定口径（按纪律要求）：
  1) 去标点归一化后，整句题面出现在 prompt 中 → 泄漏；
  2) 题面的 ≥8 字连续子串出现在 prompt 中 → 长片段泄漏（防"只差标点/半句"躲过检查）；
  3) 与 --baseline（通常是 `git show HEAD:<prompt>` 的内容）比对，把命中分为"本轮引入"与"历史已有"。
要求：**本轮引入必须为 0**；历史遗留项应在 eval/README.md 的台账中登记。
退出码：本轮引入 > 0 → 1（可直接用于 CI / pre-commit），否则 0。
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys

PUNCT = r'[\s，。！？、,.!?；;：:"""'']'


def norm(s: str) -> str:
    return re.sub(PUNCT, '', s)


def prompt_from_git(ref: str, path: str) -> str:
    try:
        out = subprocess.run(["git", "show", f"{ref}:./{path}"],
                             capture_output=True, text=True, check=True)
        return norm(out.stdout)
    except Exception as e:  # 无 git / 无该路径 → 返回空（全部记为"本轮引入"）
        print(f"[warn] 无法取 {ref}:{path}（{e}），全部命中将记为‘本轮引入’", file=sys.stderr)
        return ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompt", default="src/main/resources/prompts/intent.txt")
    ap.add_argument("--goldens", default="eval/*.jsonl")
    ap.add_argument("--baseline", default="HEAD",
                    help="对照版本（git ref）；传空字符串则跳过分类")
    args = ap.parse_args()

    cur = norm(open(args.prompt, encoding="utf-8").read())
    base = prompt_from_git(args.baseline, args.prompt) if args.baseline else ""

    introduced, historical, fragments = [], [], []
    for path in sorted(glob.glob(args.goldens)):
        name = os.path.basename(path)
        for line in open(path, encoding="utf-8"):
            if not line.strip():
                continue
            row = json.loads(line)
            text = row.get("text")
            if not text or len(text) < 4:
                continue
            tn = norm(text)
            if tn in cur:
                (historical if tn in base else introduced).append((name, text, "整句"))
                continue
            if len(tn) >= 8:
                for i in range(len(tn) - 7):
                    frag = tn[i:i + 8]
                    if frag in cur:
                        fragments.append((name, text, frag))
                        break

    print(f"本轮引入的泄漏: {len(introduced)}")
    for n, t, k in introduced:
        print(f"  [{k}] {n}: {t}")
    print(f"\n历史已有（应登记在 eval/README.md 台账）: {len(historical)}")
    for n, t, k in historical:
        print(f"  [{k}] {n}: {t}")
    print(f"\n长片段（≥8 字）泄漏: {len(fragments)}")
    for n, t, f in fragments:
        print(f"  {n}: 「{t}」 片段「{f}」")

    if introduced or fragments:
        print("\n❌ 未通过：本 prompt 含本轮引入的测试题面/长片段，请改写成非测试措辞。")
        return 1
    print("\n✅ 通过：本轮未引入题面泄漏。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
