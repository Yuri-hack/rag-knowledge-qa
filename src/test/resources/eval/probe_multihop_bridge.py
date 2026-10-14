#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据键桥接多跳：题目定义 + 原文探针验证

判据（先立后测，不合格的题丢弃并如实报告）：
  ✅ 桥接文档（采购通知）必须出现在单次检索结果里 —— 否则题目不可解（不是多跳，是死题）
  ✅ 正确型号的《设备保修条款》**必须不出现** —— 出现了说明单次检索直达，不是多跳
  ❌ 其余情况一律丢弃

注意：探针用题目原文、与评测同一检索配置（N=50 / 不截断），保证"探针通过 = 评测里真的多跳"。
"""
import json
import sys
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
NOTICE_TAG = "2026年第一季度办公设备采购通知"

# (题号, 问题原文, 目标型号, 判分事实)
QUESTIONS = [
    ("MH01", "研发中心那批 MacBook Pro 14 寸的整机保修期是多长时间？", "MRX33CH/A", "36"),
    ("MH02", "设计部的 MacBook Pro 16 寸，电池保修多久？", "MRW13CH/A", None),
    ("MH03", "产品部领的 ThinkPad X1 Carbon Gen 12 保修期是几年？", "21KC-S1M700", None),
    ("MH04", "市场部那批 MateBook X Pro 2026 的显示屏保修多长时间？", "MRGF-X26", None),
    ("MH05", "数据中心那批 Dell PowerEdge R760 服务器的整机保修期是多久？", "PER760-2U", None),
    ("MH06", "客服中心的 Dell Latitude 5450 整机保修多久？", "LAT5450-A2", None),
    ("MH07", "行政部领的 MacBook Air 13 寸电池保修多长时间？", "MRXV3CH/A", None),
    ("MH08", "财务部那批 ThinkPad T14s Gen 5 的保修期是几年？", "21LS-S0A100", None),
    ("MH09", "人力资源部领的惠普战 66 六代的显示屏保修多久？", "HP-Z66-G6", None),
    ("MH10", "设计部那批 LG UltraFine 27UP850 显示器的整机保修期是多长？", "27UP850-W", None),
]


def search(question: str, top_k: int = 50):
    qs = urllib.parse.urlencode({"query": question, "topK": top_k, "topRatio": 0})
    with urllib.request.urlopen(f"{BASE}/api/knowledge/search?{qs}", timeout=60) as r:
        data = json.loads(r.read().decode())
    return data.get("data") or []


def main():
    print(f"{'题号':6s}{'桥接文档':>10s}{'正确条款':>10s}{'条款排名':>10s}  结论")
    qualified, dropped = [], []
    for qid, question, code, fact in QUESTIONS:
        try:
            results = search(question)
        except Exception as e:
            print(f"{qid:6s}  检索失败: {e}")
            continue
        files = [r.get("fileName", "") for r in results]
        notice_ok = any(NOTICE_TAG in f for f in files)
        target_rank = next((i + 1 for i, f in enumerate(files) if code in f), None)
        # 判据：桥接文档在 + 正确条款不在（多跳成立）
        if notice_ok and target_rank is None:
            verdict = "✅ 合格（真多跳）"
            qualified.append((qid, question, code, fact))
        elif not notice_ok:
            verdict = "❌ 丢弃：桥接文档都取不到（死题）"
            dropped.append((qid, verdict))
        else:
            verdict = "❌ 丢弃：单次检索直达（非多跳）"
            dropped.append((qid, verdict))
        print(f"{qid:6s}{'✓' if notice_ok else '✗':>10s}{'✗' if target_rank is None else '✓':>10s}"
              f"{(str(target_rank) if target_rank else '-'):>10s}  {verdict}")

    print(f"\n合格 {len(qualified)} 题 / 丢弃 {len(dropped)} 题")
    print(json.dumps({"qualified": qualified, "dropped": dropped}, ensure_ascii=False, indent=2))
    return 0 if qualified else 1


if __name__ == "__main__":
    sys.exit(main())
