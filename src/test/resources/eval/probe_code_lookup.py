#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
"精确 token 查找"型题目的**预注册探针**（判据先立后测，不合格丢弃并如实报告）。

判据（用题目原文检索，N=50、topRatio=0）：
  - CL（单发编号题）：问题里已给编号 → 目标登记表行（N01 中含该编号的 chunk）**必须可检索**，
    记录其排名；不可检索说明题本身有毛病 → 丢弃。
  - CH（链式题）：桥接文档《M00-配发通知》**必须可检索** ✅
    且**没有任何 N01 登记表 chunk 含该编号**（出现即"泄漏"→ 该题退化为单发题）→ 丢弃。
    ⚠️ 只看 N01：编号出现在**配发通知**里是正常的（它是桥接文档），不算泄漏。

用法：
  python3 probe_code_lookup.py --out /tmp/probe_x.json          # 只探针、只报告
  python3 probe_code_lookup.py --out /tmp/probe_x.json --commit-qa   # 同时把合格题写入 qa.json
"""
import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "http://localhost:8080"
HERE = Path(__file__).parent
QA = HERE / "qa.json"
QUESTIONS = Path("/tmp/code_lookup_questions.json")
NOTICE_TAG = "配发通知"
REGISTER_TAG = "固定资产保修期限登记表"


def search(question, top_k=50):
    qs = urllib.parse.urlencode({"query": question, "topK": top_k, "topRatio": 0})
    with urllib.request.urlopen(f"{BASE}/api/knowledge/search?{qs}", timeout=90) as r:
        return (json.loads(r.read().decode()).get("data") or [])


def probe_one(item):
    """返回 (是否合格, 说明, 排名或 None)"""
    results = search(item["question"])
    tag = item["tag"]
    register_ranks = [i + 1 for i, r in enumerate(results)
                      if REGISTER_TAG in (r.get("fileName") or "") and tag in (r.get("content") or "")]
    notice_rank = next((i + 1 for i, r in enumerate(results)
                        if NOTICE_TAG in (r.get("fileName") or "")), None)
    if item["category"] == "code_lookup":
        if register_ranks:
            return True, f"登记表行可检索（排名 {register_ranks[0]}）", register_ranks[0]
        return False, "登记表行不可检索（题本身有毛病）", None
    # multihop_chain
    if notice_rank is None:
        return False, "桥接文档（配发通知）不可检索 → 死题", None
    if register_ranks:
        return False, f"登记表行已泄漏（排名 {register_ranks[0]}）→ 退化为单发题", register_ranks[0]
    return True, f"桥接可检索（排名 {notice_rank}）+ 登记表未泄漏", notice_rank


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--commit-qa", action="store_true")
    args = ap.parse_args()

    data = json.load(open(QUESTIONS, encoding="utf-8"))
    items = data["cl"] + data["ch"]
    qualified, dropped, ranks = [], [], {}
    print(f"{'题号':7s}{'类别':16s}{'结论':4s}  说明")
    for item in items:
        try:
            ok, why, rank = probe_one(item)
        except Exception as e:  # noqa: BLE001
            ok, why, rank = False, f"检索异常: {str(e)[:50]}", None
        if rank:
            ranks[item["id"]] = rank
        print(f"{item['id']:7s}{item['category']:16s}{'✅' if ok else '❌':4s}  {why}")
        (qualified if ok else dropped).append({**item, "probe": why})

    print(f"\n合格 {len(qualified)} / 丢弃 {len(dropped)}")
    if dropped:
        print("丢弃明细:")
        for d in dropped:
            print(f"  {d['id']}: {d['probe']}")
    if ranks:
        print(f"目标行/桥接文档排名: {ranks}")

    json.dump({"qualified": qualified, "dropped": dropped, "ranks": ranks},
              open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"已写入 {args.out}")

    if args.commit_qa:
        qa = json.load(open(QA, encoding="utf-8"))
        existing = {q["id"] for q in qa["questions"]}
        added = []
        for q in qualified:
            if q["id"] in existing:
                continue
            qa["questions"].append({
                "id": q["id"], "category": q["category"], "question": q["question"],
                "expectedFacts": q["expectedFacts"], "judgeRequired": False,
                "notes": (f"精确 token 查找题（{q['category']}）；探针：{q['probe']}；"
                          f"资产 {q['tag']}，{q['field']}期限 {q['period']} 个月"),
            })
            added.append(q["id"])
        json.dump(qa, open(QA, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        print(f"已写入 qa.json: {added}；现共 {len(qa['questions'])} 题")
    return 0 if qualified else 1


if __name__ == "__main__":
    sys.exit(main())
