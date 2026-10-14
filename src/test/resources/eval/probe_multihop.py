#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""多跳题设计探针：验证"基线单次检索必缺、agent 可恢复"。

用法：python3 probe_multihop.py
对每个候选：报告 gold chunk 在 (a) 原文 N=20/K=10 与 (b) 深池 N=50/topRatio=0 下的排名，
以及"换词重搜"能否命中（模拟 agent 第二跳）。
"""
import json
import re
import sys
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"


def norm(s):
    return re.sub(r"\s+", "", s or "")


def search(query, top_k, top_ratio):
    qs = urllib.parse.urlencode({"query": query, "topK": top_k, "topRatio": top_ratio})
    with urllib.request.urlopen(BASE + "/api/knowledge/search?" + qs, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8", "ignore"))
    return [(r.get("fileName") or "", r.get("chunkIndex"), r.get("content") or "")
            for r in data.get("data") or []]


def probe(qid, question, gold_doc_prefix, needle, reworded):
    """gold 排名：chat 配置(池20/截10) 与 深池50；换词重搜是否命中。"""
    def ranks(q):
        hits = search(q, 50, 0.0)
        out = []
        for i, (fn, ci, content) in enumerate(hits, 1):
            if fn.startswith(gold_doc_prefix) and norm(needle) in norm(content):
                out.append(i)
        return out, hits

    deep_ranks, deep_hits = ranks(question)
    # chat 实际口径：池 20、截断 10 —— gold 需在重排后的前 20 内且排名 <=10 才进上下文
    chat_visible = [r for r in deep_ranks if r <= 10]
    # 换词重搜（agent 第二跳模拟）
    re_ranks, _ = ranks(reworded)

    ok = bool(deep_ranks)
    print(f"[{qid}] {question[:36]}")
    print(f"   gold={gold_doc_prefix} | 原文深池排名={deep_ranks if deep_ranks else '缺失'}"
          f" | chat口径可见(<=10)={bool(chat_visible)} | 换词'{reworded[:14]}'排名={re_ranks if re_ranks else '缺失'}")
    return ok, (not chat_visible), bool(re_ranks)


CANDIDATES = [
    # (题干, gold 文档前缀, 定位 needle, agent 换词重搜词)
    ("MH-A", "出差时航班临时取消了，公司让我先自己掏钱处理，最多能垫多少？谁批准？",
     "04-紧急费用审批", "垫付上限 5000", "紧急费用 垫付上限 部门总监"),
    ("MH-B", "我还在试用期，身体不舒服想请假，最多能请几天？请多了会怎么样？",
     "06-员工试用期", "5 个工作日", "试用期 病假 上限 顺延"),
    ("MH-C", "婚礼定在入职第一年内，能休几天？需要提前多久跟公司报备？",
     "10-专项假期", "婚假 3 天", "婚假 天数 提前申请"),
    ("MH-D", "加班到半夜叫车回家，车费公司给报销吗？走什么流程？",
     "07-加班与调休", "实报实销", "22:00 打车 报销"),
    ("MH-E", "司龄满 5 年的老员工，年假比新员工多几天？",
     "02-职级与假期对照表", "司龄", "司龄 年假 加成 天数"),
    ("MH-F", "在客户现场干活，加班了怎么补偿？跟总部一样吗？",
     "C14-驻场加班", "1:1", "驻场 加班 调休 比例"),
]

results = {}
for qid, q, prefix, needle, reworded in CANDIDATES:
    ok, miss_chat, recoverable = probe(qid, q, prefix, needle, reworded)
    results[qid] = {"missChat": miss_chat and ok, "recoverable": recoverable}
    print()

print("== 判定 ==")
for qid, r in results.items():
    tag = "✓ 候选成立（原文 miss + 可恢复）" if (r["missChat"] and r["recoverable"]) else "✗ 不成立"
    print(f"  {qid}: {tag}")
