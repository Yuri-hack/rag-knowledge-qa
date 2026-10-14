#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把探针验证合格的多跳题加入 qa.json。

流程：先跑 probe_multihop_bridge.py（判据：桥接文档在 + 正确条款不在），
只把合格题写入；丢弃的题在输出里如实列出，不做"按结果挑题"。
"""
import json
import subprocess
import sys
from pathlib import Path

EVAL = Path(__file__).parent
QA = EVAL / "qa.json"
EXPECTED = json.load(open("/tmp/mh_expected.json", encoding="utf-8"))


def main():
    proc = subprocess.run([sys.executable, str(EVAL / "probe_multihop_bridge.py")],
                          capture_output=True, text=True)
    print(proc.stdout)
    if proc.returncode != 0:
        print("探针全部不合格或无合格题，未写入 qa.json")
        return 1
    # 取最后一段 JSON
    start = proc.stdout.rindex("{")
    payload = json.loads(proc.stdout[start:])
    qualified = payload["qualified"]
    if not qualified:
        print("无合格题，未写入")
        return 1

    qa = json.load(open(QA, encoding="utf-8"))
    existing = {q["id"] for q in qa["questions"]}
    added = []
    for qid, question, code, _ in qualified:
        if qid in existing:
            continue
        exp = EXPECTED[qid]
        qa["questions"].append({
            "id": qid,
            "category": "multihop_bridge",
            "question": question,
            "expectedFacts": [f"{exp['expected']}个月"],
            "judgeRequired": False,
            "notes": (f"数据键桥接多跳：问题不含型号（判别 token 不在问题里），"
                      f"需先检索《2026年第一季度办公设备采购通知》取得型号 {code}，"
                      f"再按型号检索《设备保修条款》。判据：单次检索取不到正确条款。"),
        })
        added.append(qid)
    json.dump(qa, open(QA, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"已加入 {len(added)} 题: {added}；qa.json 现有 {len(qa['questions'])} 题")
    print(f"丢弃 {len(payload['dropped'])} 题（如实记录，不补题）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
