#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据键桥接多跳 v2：题目定义 + 原文探针验证（batch6：配发通知 → 资产编号 → 保修登记表）

判据（先立后测，不合格丢弃并如实报告）：
  ✅ 桥接文档《2026 年新增办公设备配发通知》必须出现在单次检索结果里
     —— 否则连资产编号都拿不到，是死题而不是多跳题
  ✅ 目标资产编号**必须不出现在任何一条检索结果的内容里**
     —— 出现了说明登记表那一行被捞出来了，单次检索即可作答，不是多跳
  ❌ 其余情况一律丢弃

判分事实 = 资产编号 + 期限：编号在一行中唯一，可杜绝"答错行但数字碰巧相同"的假阳性。
"""
import importlib.util
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "http://localhost:8080"
NOTICE_TAG = "2026年新增办公设备配发通知"
FIELD_IDX = {"整机": 0, "电池": 1, "屏幕": 2}


def load_assets():
    spec = importlib.util.spec_from_file_location(
        "g2", str(Path(__file__).parent / "generate_multihop_v2.py"))
    g2 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(g2)
    _, _, assets, _ = g2.build()
    return g2, assets


def select_questions(g2, assets, want=10):
    """按字段轮转选题：要求所选字段的期限在该行内唯一（避免判分歧义）。"""
    by_field = {f: [] for f in FIELD_IDX}
    for cat, name, dep, tag in assets:
        p = g2.periods_of(tag)
        for f, i in FIELD_IDX.items():
            if p[i] not in [p[j] for j in range(3) if j != i]:
                by_field[f].append({"tag": tag, "name": name, "dep": dep,
                                    "field": f, "period": p[i]})
    picked = []
    order = ["整机", "电池", "屏幕"]
    idx = 0
    while len(picked) < want:
        f = order[idx % 3]
        if by_field[f]:
            picked.append(by_field[f].pop(0))
        idx += 1
        if idx > 300:
            break
    picked = picked[:want]
    for i, c in enumerate(picked, 1):
        c["qid"] = f"MH2{i:02d}"
        c["question"] = f"{c['dep']}的{c['name']}，资产编号和{c['field']}保修期分别是什么？"
    return picked


def search(question: str, top_k: int = 50):
    qs = urllib.parse.urlencode({"query": question, "topK": top_k, "topRatio": 0})
    with urllib.request.urlopen(f"{BASE}/api/knowledge/search?{qs}", timeout=90) as r:
        return (json.loads(r.read().decode()).get("data") or [])


def main():
    g2, assets = load_assets()
    questions = select_questions(g2, assets)
    print(f"{'题号':7s}{'桥接':>6s}{'编号泄漏':>10s}  结论")
    qualified, dropped = [], []
    for c in questions:
        try:
            results = search(c["question"])
        except Exception as e:
            print(f"{c['qid']:7s}  检索失败: {str(e)[:50]}")
            continue
        notice_ok = any(NOTICE_TAG in (r.get("fileName") or "") for r in results)
        leaked = any(c["tag"] in (r.get("content") or "") for r in results)
        if notice_ok and not leaked:
            print(f"{c['qid']:7s}{'✓':>6s}{'✗':>10s}  ✅ 合格（真多跳）")
            qualified.append(c)
        elif not notice_ok:
            print(f"{c['qid']:7s}{'✗':>6s}{'?' if leaked else '✗':>10s}  ❌ 丢弃：桥接文档取不到（死题）")
            dropped.append({"qid": c["qid"], "reason": "桥接文档取不到"})
        else:
            print(f"{c['qid']:7s}{'✓':>6s}{'✓':>10s}  ❌ 丢弃：登记表目标行被捞出（非多跳）")
            dropped.append({"qid": c["qid"], "reason": "目标行被捞出"})
    print(f"\n合格 {len(qualified)} / 丢弃 {len(dropped)}")
    json.dump({"qualified": qualified, "dropped": dropped},
              open("/tmp/mh2_probe.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("结果已写入 /tmp/mh2_probe.json")
    return 0 if qualified else 1


if __name__ == "__main__":
    sys.exit(main())
