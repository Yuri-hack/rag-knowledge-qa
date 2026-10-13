#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检索漏斗实验报告：配置 A(N=10/K=5) vs 配置 B(N=20/K=10) × 三臂。

用法：python3 funnel_report.py
读取 results/funnel{A,B}-{baseline,singleshot,agent}-*.json（取每组最新一份）。
"""
import glob
import json
from pathlib import Path

HERE = Path(__file__).parent
RESULTS = HERE / "results"

ARMS = ["baseline", "singleshot", "agent"]
CONFIGS = ["funnelA", "funnelB"]
SINGLE_HOP = {f"E{i:02d}" for i in range(1, 11)}
MULTI_FACT = {"MA01", "MA02", "MA03", "AG01", "AG02", "AZ01", "AZ02", "AZ06", "AZ07", "AZ08"}


def load(config, arm):
    files = sorted(glob.glob(str(RESULTS / f"{config}-{arm}-*.json")))
    files = [f for f in files if "judged" not in f]
    if not files:
        return None
    return json.load(open(files[-1]))


def main():
    data = {(c, a): load(c, a) for c in CONFIGS for a in ARMS}

    print("===== 每臂汇总（20 题：10 单跳对照 + 10 多事实/聚合）=====")
    header = f"{'配置-臂':16s}{'通过':>7s}{'单跳':>7s}{'多事实':>8s}{'facts 合计':>11s}{'avgTokens':>11s}{'avgTTFT':>9s}"
    print(header)
    for c in CONFIGS:
        for a in ARMS:
            d = data[(c, a)]
            if not d:
                print(f"{c}-{a:12s}  (缺数据)")
                continue
            rows = [r for r in d["rows"] if not r.get("infraError")]
            if not rows:
                continue
            passed = sum(1 for r in rows if r["passed"])
            sh = [r for r in rows if r["id"] in SINGLE_HOP]
            mf = [r for r in rows if r["id"] in MULTI_FACT]
            sh_p = sum(1 for r in sh if r["passed"])
            mf_p = sum(1 for r in mf if r["passed"])
            facts_hit = sum(len(r["factHits"]) for r in rows)
            facts_all = sum(len(r["factsExpected"]) for r in rows)
            tok = [r["tokens"] for r in rows if r.get("tokens")]
            ttft = [r["ttftMs"] for r in rows if r.get("ttftMs")]
            print(f"{c}-{a:12s}{passed:>5d}/{len(rows):<2d}{sh_p:>5d}/{len(sh):<2d}"
                  f"{mf_p:>6d}/{len(mf):<2d}{facts_hit:>7d}/{facts_all:<4d}"
                  f"{(sum(tok)//len(tok) if tok else 0):>11d}"
                  f"{(sum(ttft)//len(ttft) if ttft else 0):>9d}")

    print("\n===== 逐题 facts 覆盖（命中/期望）=====")
    ids = sorted(SINGLE_HOP | MULTI_FACT)
    print(f"{'id':7s}" + "".join(f"{c[-1]}-{a[:4]:>5s}" for c in CONFIGS for a in ARMS))
    for qid in ids:
        line = f"{qid:7s}"
        for c in CONFIGS:
            for a in ARMS:
                d = data[(c, a)]
                if not d:
                    line += f"{'--':>10s}"
                    continue
                r = next((x for x in d["rows"] if x["id"] == qid), None)
                if r is None:
                    line += f"{'--':>10s}"
                else:
                    mark = "⚠" if r.get("infraError") else ("✓" if r["passed"] else "✗")
                    line += f"{mark}{len(r['factHits'])}/{len(r['factsExpected'])}".ljust(10)
        print(line)

    print("\n===== 工具调用与检索故障（agent 臂，验证评测口径）=====")
    for c in CONFIGS:
        d = data[(c, "agent")]
        if not d:
            continue
        rows = d["rows"]
        calls = sum(r.get("toolCalls") or 0 for r in rows)
        fails = sum(len(r.get("toolFailures") or []) for r in rows)
        infra_rows = [r["id"] for r in rows if r.get("infraError")]
        print(f"  {c}: 工具调用 {calls} 次, 工具失败 {fails} 次, infra 剔除题 {infra_rows}")


if __name__ == "__main__":
    main()
