#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""评测 runner（关键词命中层）。缓存自动关/flush/恢复；fact 去空白子串匹配；infra 故障重试并剔除分母。"""
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
from typing import NamedTuple

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
    """清空 rag:* 并二次校验，失败中止。"""
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


class TurnResult(NamedTuple):
    """一轮问答的完整结果；infra_retries 为 infra 故障重试次数。"""
    answer: str
    usage: dict | None
    ttft_ms: float | None
    total_ms: float
    error: str | None
    prompt_version: str | None
    termination: str | None
    infra_retries: int = 0


def stream_answer(question: str, endpoint: str = "baseline", mode: str = "agent",
                  timeout: int = 120, session: str = None, rewrite: bool = True):
    """调问答端点；session 非空时带 sessionId + rewrite（D6 多轮）。"""
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
    answer, usage, error, prompt_version, termination = [], None, None, None, None
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
                    termination = obj.get("termination")
                    break
    except Exception as e:  # noqa: BLE001
        error = f"request failed: {e}"
    total = (time.time() - t0) * 1000
    return TurnResult("".join(answer), usage, ttft, total, error, prompt_version, termination)


LLM_FAILURE_MARKER = "[模型调用失败]"


def is_infra_failure(answer: str, error, termination) -> bool:
    """能力失败 vs 基础设施失败（网络/限流/5xx）。"""
    if termination == "LLM_FAILURE":
        return True
    if error and "request failed" in str(error):
        return True
    return bool(answer and answer.startswith(LLM_FAILURE_MARKER))


def ask_with_retry(question: str, retries: int = 2, **kw):
    """infra 失败自动重试（能力失败不重试），重试次数入结果。"""
    retries_left = retries
    while True:
        r = stream_answer(question, **kw)
        if not is_infra_failure(r.answer, r.error, r.termination) or retries_left <= 0:
            return r
        print(f"    [infra] 基础设施故障（{str(r.error or r.termination)[:80]}），重试")
        retries_left -= 1
        time.sleep(2)
    # unreachable


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
        last: TurnResult | None = None
        for i, t in enumerate(turns):
            last = ask_with_retry(
                t, endpoint=args.endpoint, mode=args.mode, session=session_id, rewrite=rewrite)
            turn_answers.append(last.answer)
            turn_tokens.append((last.usage or {}).get("totalTokens") or 0)
            if session_id and i < len(turns) - 1:
                time.sleep(1.0)  # 等 detached 会话追加落库
        answer = last.answer
        ans_n = norm(answer)

        # infra 故障（重试后仍失败）：不计入能力分母
        infra = is_infra_failure(last.answer, last.error, last.termination)

        if q.get("shouldRetrieve", True):
            fact_hits = [f for f in facts if f in ans_n]
            if infra:
                passed = None
            elif q.get("judgeRequired"):
                passed = None  # 关键词层假阳性，交 judge
            else:
                passed = bool(facts) and len(fact_hits) == len(facts)
            names, err2 = search_files(turns[-1])
            names_n = [norm(n) for n in names]
            doc_hit = bool(q.get("expectDocKeyword")) and any(
                norm(q["expectDocKeyword"]) in n for n in names_n)
        else:
            fact_hits, passed = [], score_no_retrieval(ans_n, q)
            if infra:
                passed = None
            doc_hit = None
            names, err2 = [], None

        row = {
            "id": qid, "category": cat, "question": q.get("question") or turns[-1],
            "passed": passed, "judgeRequired": bool(q.get("judgeRequired")),
            "infraError": infra, "infraRetries": last.infra_retries,
            "termination": last.termination,
            "factHits": fact_hits, "factsExpected": facts,
            "shouldRetrieve": q.get("shouldRetrieve", True),
            "retrievalHit": doc_hit, "retrievedFiles": names[:10],
            "tokens": (sum(turn_tokens) if any(turn_tokens) else None),
            "ttftMs": round(last.ttft_ms or 0),
            "totalMs": round(last.total_ms), "error": last.error or err2,
            "answer": answer[:2000],
        }
        if len(turns) > 1:
            row["turns"] = turns
            row["turnAnswers"] = [a[:800] for a in turn_answers]
            row["sessionId"] = session_id
        rows.append(row)
        mark = "⚠infra" if infra else ("✓" if passed else ("⏳judge" if passed is None else "✗"))
        print(f"[{mark}] {qid} ({cat}) tokens={rows[-1]['tokens']} "
              f"ttft={rows[-1]['ttftMs']}ms total={rows[-1]['totalMs']}ms "
              f"facts={len(fact_hits)}/{len(facts)}")
        if last.error or err2:
            print(f"    !! {last.error or err2}")

    # 汇总（infra 故障题不计入能力分母，单独汇报）
    print("\n===== 汇总（按 category，infra 不计入分母）=====")
    cats = {}
    for r in rows:
        cats.setdefault(r["category"], []).append(r)
    for cat, rs in cats.items():
        cap = [r for r in rs if not r.get("infraError")]
        p = sum(1 for r in cap if r["passed"])
        ret = [r for r in cap if r["shouldRetrieve"]]
        tok = [r["tokens"] for r in ret if r["tokens"]]
        tt = [r["ttftMs"] for r in rs if r["ttftMs"]]
        ttot = [r["totalMs"] for r in rs if r["totalMs"]]
        infra_n = len(rs) - len(cap)
        print(f"{cat:24s} {p}/{len(cap)} 通"
              + (f"  infra={infra_n}" if infra_n else "")
              + (f"  avgTokens={sum(tok)//len(tok)}" if tok else "")
              + (f"  avgTTFT={int(sum(tt)/len(tt))}ms" if tt else "")
              + (f"  avgTotal={int(sum(ttot)/len(ttot))}ms" if ttot else ""))
    cap_rows = [r for r in rows if not r.get("infraError")]
    ok = sum(1 for r in cap_rows if r["passed"])
    infra_total = len(rows) - len(cap_rows)
    print(f"\nTOTAL {ok}/{len(cap_rows)}  infra={infra_total}  label={args.label} "
          f"endpoint={args.endpoint} prompt={(last.prompt_version if last else None)} rewrite={args.rewrite}")

    RESULTS.mkdir(exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RESULTS / f"{args.label}-{ts}.json"
    out.write_text(json.dumps({
        "label": args.label, "endpoint": args.endpoint, "promptVersion": (last.prompt_version if last else None),
        "rewrite": rewrite,
        "cache": cache_report, "finishedAt": ts, "rows": rows,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[saved] {out}")


if __name__ == "__main__":
    main()
