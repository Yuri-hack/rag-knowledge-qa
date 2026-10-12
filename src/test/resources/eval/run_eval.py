#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
D3 评测 runner（迭代层：关键词命中判定）

用法：
  python3 run_eval.py --label baseline-qwen-plus            # 全量跑（关缓存 + flush）
  python3 run_eval.py --label agent-v01 --endpoint agent    # agent 端点
  python3 run_eval.py --no-flush --label quick              # 不动缓存（仅调试用）

口径（与 qa.json meta 一致）：
  - 评测期间通过 /api/admin/cache/toggle 关闭三级缓存（跑完自动恢复），
    并 flush rag:* 兜底；flush 后二次扫描校验，失败直接中止，防串轮污染
  - fact 匹配：答案与 expectedFacts 均去空白后做子串匹配，全部命中才 pass
  - shouldRetrieve=false 的题：拒答/兜底启发式判定（不做事实匹配）
  - 检索命中：/api/knowledge/search topK=10 的 fileName 中出现 expectDocKeyword
"""
import argparse
import atexit
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


def redis_cli(*args: str) -> str:
    r = subprocess.run(["docker", "exec", "rag-redis-dev", "redis-cli", *args],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"redis-cli 失败: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def flush_cache() -> dict:
    """清空 rag:* 并二次扫描校验，失败抛异常中止评测。返回 flush 报告。"""
    keys = [k for k in redis_cli("--scan", "--pattern", "rag:*").splitlines() if k.strip()]
    for k in keys:
        redis_cli("DEL", k)
    remaining = [k for k in redis_cli("--scan", "--pattern", "rag:*").splitlines() if k.strip()]
    report = {"flushedKeys": len(keys), "remainingAfterFlush": len(remaining)}
    print(f"[cache] flushed {len(keys)} keys, remaining {len(remaining)}")
    if remaining:
        raise RuntimeError(f"flush 校验失败，仍有 {len(remaining)} 个 key 残留: {remaining[:5]}")
    return report


def set_cache_enabled(enabled: bool) -> dict:
    url = BASE + "/api/admin/cache/toggle?enabled=" + ("true" if enabled else "false")
    req = urllib.request.Request(url, data=b"", method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode("utf-8", "ignore"))


def stream_answer(question: str, endpoint: str = "baseline", mode: str = "agent",
                  timeout: int = 120, session: str = None, rewrite: bool = True):
    """调问答端点，返回 (answer, usage, ttft_ms, total_ms, error, prompt_version)。
    session 非空时带 sessionId（D6 多轮）+ rewrite 开关。"""
    if endpoint in ("agent", "singleshot"):
        qs = {"question": question, "mode": mode, "maxSteps": 6, "debug": "true"}
        url = BASE + "/api/chat/agent?" + urllib.parse.urlencode(qs)
    else:
        qs = {"question": question}
        if session:
            qs["sessionId"] = session
            qs["rewrite"] = "true" if rewrite else "false"
        url = BASE + "/api/chat/rag/stream?" + urllib.parse.urlencode(qs)
    t0 = time.time()
    answer, usage, error, prompt_version = [], None, None, None
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
                    prompt_version = obj.get("promptVersion")  # BC02 教训：结果必须能对回 prompt 版本
                    break
    except Exception as e:  # noqa: BLE001
        error = f"request failed: {e}"
    total = (time.time() - t0) * 1000
    return "".join(answer), usage, ttft, total, error, prompt_version


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
    ap.add_argument("--endpoint", default="baseline", choices=["baseline", "agent", "singleshot"])
    ap.add_argument("--mode", default="agent")
    ap.add_argument("--rewrite", default="true", choices=["true", "false"],
                    help="D6 多轮 query 改写开关（可证伪实验：false 应使多轮题失败）")
    ap.add_argument("--no-flush", action="store_true")
    ap.add_argument("--ids", default="", help="逗号分隔，只跑指定题目（稳定性复测用）")
    args = ap.parse_args()
    rewrite = args.rewrite == "true"

    qa = json.loads((HERE / "qa.json").read_text(encoding="utf-8"))
    questions = qa["questions"]
    if args.ids:
        want = {x.strip() for x in args.ids.split(",") if x.strip()}
        questions = [q for q in questions if q["id"] in want]

    if not args.no_flush:
        set_cache_enabled(False)
        atexit.register(set_cache_enabled, True)  # 跑完（含异常退出）自动恢复缓存
        cache_report = {"disabledDuringRun": True, **flush_cache()}
    else:
        cache_report = {"disabledDuringRun": False, "note": "--no-flush 调试模式，未动缓存"}

    run_ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    rows = []
    for q in questions:
        qid, cat = q["id"], q["category"]
        facts = [norm(f) for f in q.get("expectedFacts", [])]

        # D6 多轮：turns 非空时逐轮发送（同一 sessionId），末轮答案计分
        turns = q.get("turns") or [q["question"]]
        session_id = None
        if len(turns) > 1:
            session_id = f"eval-{args.label}-{run_ts}-{qid}"  # 每次运行独立，避免会话串场
        turn_answers, turn_tokens = [], []
        usage, ttft, error, prompt_version = None, None, None, None
        for i, t in enumerate(turns):
            answer, usage, ttft, total, error, prompt_version = stream_answer(
                t, endpoint=args.endpoint, mode=args.mode, session=session_id, rewrite=rewrite)
            turn_answers.append(answer)
            turn_tokens.append((usage or {}).get("totalTokens") or 0)
            if session_id and i < len(turns) - 1:
                time.sleep(1.0)  # 等上一轮的 detached 会话追加落库（毫秒级竞态，真实用户间隔秒级不受影响）
        answer = turn_answers[-1]
        ans_n = norm(answer)

        if q.get("shouldRetrieve", True):
            fact_hits = [f for f in facts if f in ans_n]
            if q.get("judgeRequired"):
                passed = None  # 关键词层已证明会假阳性，交终评 judge
            else:
                passed = bool(facts) and len(fact_hits) == len(facts)
            names, err2 = search_files(turns[-1])
            names_n = [norm(n) for n in names]
            doc_hit = bool(q.get("expectDocKeyword")) and any(
                norm(q["expectDocKeyword"]) in n for n in names_n)
        else:
            fact_hits, passed = [], score_no_retrieval(ans_n, q)
            doc_hit = None
            names, err2 = [], None

        row = {
            "id": qid, "category": cat, "question": q.get("question") or turns[-1],
            "passed": passed, "judgeRequired": bool(q.get("judgeRequired")),
            "factHits": fact_hits, "factsExpected": facts,
            "shouldRetrieve": q.get("shouldRetrieve", True),
            "retrievalHit": doc_hit, "retrievedFiles": names[:10],
            "tokens": (sum(turn_tokens) if any(turn_tokens) else None),
            "ttftMs": round(ttft or 0),
            "totalMs": round(total), "error": error or err2,
            "answer": answer[:2000],
        }
        if len(turns) > 1:
            row["turns"] = turns
            row["turnAnswers"] = [a[:800] for a in turn_answers]
            row["sessionId"] = session_id
        rows.append(row)
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
    print(f"\nTOTAL {ok}/{len(rows)}  label={args.label} endpoint={args.endpoint} "
          f"prompt={prompt_version} rewrite={args.rewrite}")

    RESULTS.mkdir(exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RESULTS / f"{args.label}-{ts}.json"
    out.write_text(json.dumps({
        "label": args.label, "endpoint": args.endpoint, "promptVersion": prompt_version,
        "rewrite": rewrite,
        "cache": cache_report, "finishedAt": ts, "rows": rows,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[saved] {out}")


if __name__ == "__main__":
    main()
