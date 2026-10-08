#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
D3 评测 runner（迭代层：关键词命中判定）

用法：
  python3 run_eval.py --label baseline-qwen-plus            # 全量跑 + flush 缓存
  python3 run_eval.py --label agent-v01 --endpoint agent    # 预留：D4 agent 端点
  python3 run_eval.py --no-flush --label quick              # 不清缓存（仅调试用）

口径（与 qa.json meta 一致）：
  - 每次全量评测前 flush rag:* 缓存（精确/语义答案/语义文档），防串轮污染
  - fact 匹配：答案与 expectedFacts 均去空白后做子串匹配，全部命中才 pass
  - shouldRetrieve=false 的题：拒答/兜底启发式判定（不做事实匹配）
  - 检索命中：/api/knowledge/search topK=10 的 fileName 中出现 expectDocKeyword
"""
import argparse
import json
import re
import subprocess
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

BASE = "http://localhost:8080"
HERE = Path(__file__).parent
RESULTS = HERE / "results"


def norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def flush_cache():
    r = subprocess.run(
        ["docker", "exec", "rag-redis-dev", "redis-cli", "--scan", "--pattern", "rag:*"],
        capture_output=True, text=True)
    keys = [k for k in r.stdout.split() if k.strip()]
    for k in keys:
        subprocess.run(["docker", "exec", "rag-redis-dev", "redis-cli", "DEL", k],
                       capture_output=True)
    print(f"[cache] flushed {len(keys)} keys")


def stream_answer(question: str, timeout: int = 90):
    """调 /api/chat/rag/stream，返回 (answer, usage, ttft_ms, total_ms, error)"""
    url = BASE + "/api/chat/rag/stream?" + urllib.parse.urlencode({"question": question})
    t0 = time.time()
    answer, usage, error = [], None, None
    ttft = None
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            for raw in resp:
                line = raw.decode("utf-8", "ignore").strip()
                if not line.startswith("data:"):
                    continue
                if ttft is None:
                    ttft = (time.time() - t0) * 1000
                payload = line[5:].strip()
                try:
                    obj = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                if obj.get("content"):
                    answer.append(obj["content"])
                if obj.get("errorMessage"):
                    error = obj["errorMessage"]
                if obj.get("finished"):
                    usage = obj.get("usage")
                    break
    except Exception as e:  # noqa: BLE001
        error = f"request failed: {e}"
    total = (time.time() - t0) * 1000
    return "".join(answer), usage, ttft, total, error


def search_files(query: str, top_k: int = 10):
    url = BASE + "/api/knowledge/search?" + urllib.parse.urlencode(
        {"query": query, "topK": top_k})
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8", "ignore"))
    except Exception as e:  # noqa: BLE001
        return [], str(e)

    names = []

    def walk(o):
        if isinstance(o, dict):
            if "fileName" in o:
                names.append(str(o.get("fileName") or ""))
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(data)
    return names, None


def score_no_retrieval(answer_norm: str, q: dict) -> bool:
    """拒答/闲聊题启发式：不编造具体数字/不硬答制度内容即算过（粗判，终评靠 judge）"""
    if q["category"] == "needs_web":
        # 允许联网后正确回答（D4 后），或诚实说明无法提供
        return True  # 迭代层不判 needs_web 正确性，只记录行为（联网/拒答/幻觉），终评 judge
    fabricated = re.search(r"\d+(\.\d+)?\s*(亿|万元|亿|元)", answer_norm)
    refuses = any(k in answer_norm for k in
                  ["无法", "没有", "不掌握", "未公开", "抱歉", "不能", "建议", "知识库", "不予"])
    return (not fabricated) or refuses


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="baseline")
    ap.add_argument("--endpoint", default="baseline", choices=["baseline", "agent"])
    ap.add_argument("--no-flush", action="store_true")
    ap.add_argument("--ids", default="", help="逗号分隔，只跑指定题目（稳定性复测用）")
    args = ap.parse_args()

    qa = json.loads((HERE / "qa.json").read_text(encoding="utf-8"))
    questions = qa["questions"]
    if args.ids:
        want = {x.strip() for x in args.ids.split(",") if x.strip()}
        questions = [q for q in questions if q["id"] in want]

    if not args.no_flush:
        flush_cache()

    rows = []
    for q in questions:
        qid, cat = q["id"], q["category"]
        facts = [norm(f) for f in q.get("expectedFacts", [])]
        answer, usage, ttft, total, error = stream_answer(q["question"])
        ans_n = norm(answer)

        if q.get("shouldRetrieve", True):
            fact_hits = [f for f in facts if f in ans_n]
            if q.get("judgeRequired"):
                passed = None  # 关键词层已证明会假阳性，交终评 judge
            else:
                passed = bool(facts) and len(fact_hits) == len(facts)
            names, err2 = search_files(q["question"])
            names_n = [norm(n) for n in names]
            doc_hit = bool(q.get("expectDocKeyword")) and any(
                norm(q["expectDocKeyword"]) in n for n in names_n)
        else:
            fact_hits, passed = [], score_no_retrieval(ans_n, q)
            doc_hit = None
            names, err2 = [], None

        rows.append({
            "id": qid, "category": cat, "question": q["question"],
            "passed": passed, "judgeRequired": bool(q.get("judgeRequired")),
            "factHits": fact_hits, "factsExpected": facts,
            "shouldRetrieve": q.get("shouldRetrieve", True),
            "retrievalHit": doc_hit, "retrievedFiles": names[:10],
            "tokens": (usage or {}).get("totalTokens"), "ttftMs": round(ttft or 0),
            "totalMs": round(total), "error": error or err2,
            "answer": answer[:500],
        })
        mark = "✓" if passed else ("⏳judge" if passed is None else "✗")
        print(f"[{mark}] {qid} ({cat}) tokens={rows[-1]['tokens']} "
              f"ttft={rows[-1]['ttftMs']}ms total={rows[-1]['totalMs']}ms "
              f"facts={len(fact_hits)}/{len(facts)}")
        if error or err2:
            print(f"    !! {error or err2}")

    # 汇总
    print("\n===== 汇总（按 category）=====")
    cats = {}
    for r in rows:
        cats.setdefault(r["category"], []).append(r)
    for cat, rs in cats.items():
        p = sum(1 for r in rs if r["passed"])
        ret = [r for r in rs if r["shouldRetrieve"]]
        tok = [r["tokens"] for r in ret if r["tokens"]]
        tt = [r["ttftMs"] for r in rs if r["ttftMs"]]
        ttot = [r["totalMs"] for r in rs if r["totalMs"]]
        print(f"{cat:24s} {p}/{len(rs)} 通"
              + (f"  avgTokens={sum(tok)//len(tok)}" if tok else "")
              + (f"  avgTTFT={int(sum(tt)/len(tt))}ms" if tt else "")
              + (f"  avgTotal={int(sum(ttot)/len(ttot))}ms" if ttot else ""))
    ok = sum(1 for r in rows if r["passed"])
    print(f"\nTOTAL {ok}/{len(rows)}  label={args.label} endpoint={args.endpoint}")

    RESULTS.mkdir(exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RESULTS / f"{args.label}-{ts}.json"
    out.write_text(json.dumps({
        "label": args.label, "endpoint": args.endpoint,
        "flushed": not args.no_flush, "finishedAt": ts, "rows": rows,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[saved] {out}")


if __name__ == "__main__":
    main()
