#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验收探针：Milvus 2.5 服务端混合检索下，精确业务编号是否仍能命中。

背景（docs/milvus-2.5-upgrade-checklist.md 结论四）：
RRF 融合的是**排名**而非分数——"稀疏第1、稠密第50"的融合分可能低于"两路都第5"。
而 sparse 通道对资产编号是强信号（spike 实测 score 4.7）。
所以升级到服务端混合检索后，必须专门验证精确编号仍能进最终结果，否则需要
改用 WeightedRanker 提高稀疏权重（或按标量字段做精确过滤）。

用法：
  python3 probe_exact_id.py                 # 默认查 MH204 的编号
  python3 probe_exact_id.py YC-HY-2026-0002 10
"""
import json
import sys
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
DEFAULT_CODE = "YC-HY-2026-0002"      # MH204 的目标编号（升级前 agent 第二跳失败的那个）
DEFAULT_TOPK = 10


def search(query: str, top_k: int):
    qs = urllib.parse.urlencode({"query": query, "topK": top_k, "topRatio": 0})
    with urllib.request.urlopen(f"{BASE}/api/knowledge/search?{qs}", timeout=90) as r:
        return (json.loads(r.read().decode()).get("data") or [])


def main():
    code = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CODE
    top_k = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_TOPK
    results = search(code, top_k)
    print(f"查询: {code}   topK={top_k}   返回 {len(results)} 条")
    hit_rank = None
    for i, r in enumerate(results, 1):
        content = r.get("content") or ""
        contains = code in content
        if contains and hit_rank is None:
            hit_rank = i
        print(f"  {i:2d}. 含目标编号={'✓' if contains else '✗'} "
              f"rerank={r.get('rerankScore')} | {content[:50].strip()}")
    print()
    if hit_rank:
        print(f"结果：目标编号命中，排名第 {hit_rank} 位 → 通过（K≥{hit_rank} 即可作答）")
        return 0
    print("结果：目标编号未进结果 → 失败，RRF 稀释了精确匹配，需上 WeightedRanker 或标量过滤")
    return 1


if __name__ == "__main__":
    sys.exit(main())
