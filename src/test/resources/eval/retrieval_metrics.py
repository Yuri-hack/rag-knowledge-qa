#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检索层度量：gold chunk 在检索结果中的排名分布，回答"瓶颈在召回还是排序"。

用法：python3 retrieval_metrics.py [--topk 50]

口径：
- 查询用题目原文（多轮题取末轮），topK=50 & topRatio=0（拿 rerank 后的完整候选池）
- goldChunks 来自 retrieval-annotations.json（expectDocKeyword 锚定 + 人工补全）
- 关键指标：
    hit@5      —— 评测实际用的就是 5 条上下文，这是"能不能答对"的检索前提
    hit@10/@50 —— 池子更深时的上限，用来判断"扩池"能否救
    chunkRecall@k —— 多 gold 题（对比/聚合类）的覆盖比例
    MRR / bestRank 分布 —— 排序质量
- 瓶颈判读：
    gold 在 top5 外但在 top50 内 → 排序/截断问题（扩池或调 rerank 有效）
    gold 不在 top50           → 召回问题（混合检索/元数据过滤才有意义）

产出：results/retrieval-metrics-<ts>.json
"""
import argparse
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).parent
RESULTS = HERE / "results"
BASE = "http://localhost:8080"

# 末轮为纯指代、必须经 D6 改写才能检索的题：原文度量必然 miss，单列不计入汇总
REWRITE_REQUIRED = {"MT01", "MT02", "MT03"}


def search(query: str, top_k: int):
    """返回 [(documentId, chunkIndex, fileName, rerankScore)]，保持检索排序。"""
    qs = urllib.parse.urlencode({"query": query, "topK": top_k, "topRatio": 0.0})
    with urllib.request.urlopen(BASE + "/api/knowledge/search?" + qs, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8", "ignore"))
    out = []
    for r in data.get("data") or []:
        out.append((r.get("documentId"), r.get("chunkIndex"), r.get("fileName"),
                    r.get("rerankScore") or r.get("similarity")))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--topk", type=int, default=50)
    ap.add_argument("--ids", default="", help="逗号分隔，只跑指定题")
    args = ap.parse_args()

    ann = json.loads((HERE / "retrieval-annotations.json").read_text(encoding="utf-8"))
    questions = ann["questions"]
    if args.ids:
        want = {x.strip() for x in args.ids.split(",") if x.strip()}
        questions = {k: v for k, v in questions.items() if k in want}

    rows = []
    for qid, a in questions.items():
        if a.get("status") == "excluded":
            continue
        # 按 (fileName, chunkIndex) 比对：documentId 每次全量重建都会重新生成（DOC_<时间戳>_<随机>），
        # 早期按 documentId 比对会导致重建后全部 miss（2026-10-15 实测全 0/38 即此因）
        gold_chunks = [(g["fileName"], g["chunkIndex"]) for g in a["goldChunks"]]
        gold_docs = {g["fileName"] for g in a["goldChunks"]}
        if not gold_chunks:
            continue
        t0 = time.time()
        try:
            got = search(a["query"], args.topk)
            error = None
        except Exception as e:  # noqa: BLE001
            got, error = [], str(e)[:80]

        key_to_rank = {(f, c): i + 1 for i, (_, c, f, _) in enumerate(got)}
        ranks = [key_to_rank.get(k) for k in gold_chunks]
        best = min([r for r in ranks if r], default=None)
        doc_hit_ranks = {}
        for i, (_, c, f, _) in enumerate(got, 1):
            if f in gold_docs and f not in doc_hit_ranks:
                doc_hit_ranks[f] = i

        def chunk_recall(k):
            return sum(1 for r in ranks if r and r <= k) / len(gold_chunks)

        def doc_coverage(k):
            return sum(1 for d in gold_docs if doc_hit_ranks.get(d, 10 ** 6) <= k) / len(gold_docs)

        rows.append({
            "id": qid, "category": a["category"], "method": a["method"], "query": a["query"],
            "goldChunks": len(gold_chunks), "goldDocs": len(gold_docs),
            "goldRanks": ranks, "bestRank": best,
            "chunkRecall@5": round(chunk_recall(5), 3),
            "chunkRecall@10": round(chunk_recall(10), 3),
            "chunkRecall@50": round(chunk_recall(args.topk), 3),
            "docCoverage@5": round(doc_coverage(5), 3),
            "docCoverage@10": round(doc_coverage(10), 3),
            "hit@5": any(r and r <= 5 for r in ranks),
            "hit@10": any(r and r <= 10 for r in ranks),
            "hit@50": any(r and r <= args.topk for r in ranks),
            "returned": len(got), "error": error,
            "elapsedMs": round((time.time() - t0) * 1000),
        })
        rows[-1]["rewriteRequired"] = qid in REWRITE_REQUIRED
        mark = "✓" if rows[-1]["hit@5"] else ("△top50" if rows[-1]["hit@50"] else "✗miss")
        if rows[-1]["rewriteRequired"]:
            mark = "↻改写"
        print(f"[{mark:7s}] {qid:6s} bestRank={str(best):>4s} "
              f"chunkRecall@5={rows[-1]['chunkRecall@5']:.2f} docCov@5={rows[-1]['docCoverage@5']:.2f} "
              f"gold={len(gold_chunks)}")
        if error:
            print(f"    !! {error}")

    scored = [r for r in rows if not r.get("rewriteRequired")]
    rows = scored
    n = len(rows)
    if n:
        def mean(key):
            return round(sum(r[key] for r in rows) / n, 3)

        print(f"\n===== 汇总（{n} 题，topK={args.topk}）=====")
        print(f"hit@5={sum(r['hit@5'] for r in rows)}/{n}   "
              f"hit@10={sum(r['hit@10'] for r in rows)}/{n}   "
              f"hit@{args.topk}={sum(r['hit@50'] for r in rows)}/{n}")
        print(f"chunkRecall@5={mean('chunkRecall@5')}  @10={mean('chunkRecall@10')}  @{args.topk}={mean('chunkRecall@50')}")
        print(f"docCoverage@5={mean('docCoverage@5')}  @10={mean('docCoverage@10')}")
        mrr = round(sum(1 / r["bestRank"] for r in rows if r["bestRank"]) / n, 3)
        print(f"MRR={mrr}")

        buckets = {"1": 0, "2-3": 0, "4-5": 0, "6-10": 0, "11-20": 0, "21-50": 0, ">50/缺失": 0}
        for r in rows:
            b = r["bestRank"]
            if not b:
                buckets[">50/缺失"] += 1
            elif b == 1:
                buckets["1"] += 1
            elif b <= 3:
                buckets["2-3"] += 1
            elif b <= 5:
                buckets["4-5"] += 1
            elif b <= 10:
                buckets["6-10"] += 1
            elif b <= 20:
                buckets["11-20"] += 1
            else:
                buckets["21-50"] += 1
        print("gold 最佳排名分布:", buckets)

        miss5 = [r["id"] for r in rows if not r["hit@5"]]
        if miss5:
            print(f"\ntop5 未命中（评测实际条件下检索失败）: {miss5}")
            in_pool = [r["id"] for r in rows if not r["hit@5"] and r["hit@50"]]
            absent = [r["id"] for r in rows if not r["hit@50"]]
            print(f"  其中池内有（排序/截断问题）: {in_pool}")
            print(f"  池内也没有（召回问题）: {absent}")

    RESULTS.mkdir(exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RESULTS / f"retrieval-metrics-{ts}.json"
    out.write_text(json.dumps({"topK": args.topk, "finishedAt": ts, "rows": rows},
                              ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[saved] {out}")


if __name__ == "__main__":
    main()
