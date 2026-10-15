#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
"精确 token 查找"型题目生成器（CL 单发编号题 + CH 链式题）。

动机：现有 25 道测量题里只有 5 道需要"精确定位某个 token"（编号），单题权重 20%，
任何 ±1 题都能翻转"混合检索是否有收益"的结论。本脚本专门造这类题，且**每条题覆盖不同资产**
（修掉 MH 那批"3 题同一资产"的缺陷）。

不新建语料：复用 generate_multihop_v2.build() 的 batch6 资产与 periods_of() 期限。

约束：
  - 与 MH 系列已用资产不重叠；CL 与 CH 之间也不重叠（每条题一个资产）；
  - 所选字段的期限在该行内唯一（避免"答错字段但数字相同"的判分歧义）；
  - 优先选"期限在全库较稀有"的字段 —— CL 题的期望事实只有期限，
    若多数题期望同一个数字，模型答错行也可能碰巧命中（假阳性）。
"""
import importlib.util
import json
from collections import Counter
from pathlib import Path

HERE = Path(__file__).parent
QA = HERE / "qa.json"
OUT = Path("/tmp/code_lookup_questions.json")
FIELD_IDX = {"整机": 0, "电池": 1, "屏幕": 2}


def main():
    spec = importlib.util.spec_from_file_location("g2", str(HERE / "generate_multihop_v2.py"))
    g2 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(g2)
    _, _, assets, _ = g2.build()

    # 已用资产：MH 系列（含 MH2/MH3）expectedFacts 里以 YC- 开头的编号
    qa = json.load(open(QA, encoding="utf-8"))
    mh_used = set()
    for q in qa["questions"]:
        if q["id"].startswith("MH"):
            for fact in q.get("expectedFacts") or []:
                if fact.startswith("YC-"):
                    mh_used.add(fact)
    print(f"已用资产（MH 系列）: {sorted(mh_used)}")

    # 期限稀有度：优先选稀有的，降低 CL 题的假阳性
    freq = {f: Counter(g2.periods_of(tag)[i] for _, _, _, tag in assets)
            for f, i in FIELD_IDX.items()}
    candidates = []
    for cat, name, dep, tag in assets:
        if tag in mh_used:
            continue
        periods = g2.periods_of(tag)
        for field, idx in FIELD_IDX.items():
            if periods[idx] not in [periods[j] for j in range(3) if j != idx]:
                candidates.append({
                    "tag": tag, "name": name, "dep": dep, "field": field,
                    "period": periods[idx], "rarity": freq[field][periods[idx]],
                })
    candidates.sort(key=lambda c: (c["rarity"], c["tag"]))

    taken = set(mh_used)

    def pick(count):
        out = []
        for c in candidates:
            if len(out) >= count:
                break
            if c["tag"] in taken:
                continue
            taken.add(c["tag"])
            out.append({k: v for k, v in c.items() if k != "rarity"})
        return out

    cl = pick(6)
    ch = pick(8)
    for i, c in enumerate(cl, 1):
        c["id"] = f"CL{i:02d}"
        c["category"] = "code_lookup"
        c["question"] = f"资产编号 {c['tag']} 的设备，{c['field']}保修期是多久？"
        c["expectedFacts"] = [f"{c['period']}个月"]
    for i, c in enumerate(ch, 1):
        c["id"] = f"CH{i:02d}"
        c["category"] = "multihop_chain"
        c["question"] = f"{c['dep']}的{c['name']}，资产编号和{c['field']}保修期分别是什么？"
        c["expectedFacts"] = [c["tag"], f"{c['period']}个月"]

    json.dump({"cl": cl, "ch": ch}, open(OUT, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    print(f"\n单发编号题 CL（{len(cl)}）:")
    for c in cl:
        print(f"  {c['id']} [{c['tag']}] {c['field']} → {c['period']}个月   {c['question']}")
    print(f"\n链式题 CH（{len(ch)}）:")
    for c in ch:
        print(f"  {c['id']} [{c['tag']}] {c['field']} → {c['period']}个月   {c['question']}")

    tags = [c["tag"] for c in cl + ch]
    assert len(tags) == len(set(tags)), "CL/CH 资产重复"
    assert not (set(tags) & mh_used), "与 MH 资产重复"
    print(f"\nCL 期望期限: {sorted(c['period'] for c in cl)}")
    print(f"CH 期望期限: {sorted(c['period'] for c in ch)}")
    print(f"资产唯一性校验通过：{len(tags)} 条题覆盖 {len(set(tags))} 个不同资产，与 MH 无重叠")
    print(f"已写入 {OUT}")


if __name__ == "__main__":
    main()
