#!/usr/bin/env python3
"""LLM-as-judge 终评 runner（可复跑）——judge prompt 内嵌冻结。

背景：四臂对比（four-arm-comparison-20261008.md）的 judge 原为外部一次性执行，
prompt 未入库，换台机器不可复现。本脚本把 judge 落成代码：
prompt 冻结在下方 JUDGE_SYSTEM_PROMPT（v1，2026-10-12），温度 0，改动即口径重置。

用法：
  export DASHSCOPE_API_KEY=sk-...
  python3 run_judge.py --results results/arm-C-agent-v021-20261008-161604.json
  python3 run_judge.py --results results/xxx.json --rows all          # 默认只评 judgeRequired 题
  python3 run_judge.py --results results/xxx.json --ids S01,MA01
  python3 run_judge.py --results results/xxx.json --model qwen-max   # 本账号可用（qwen3-max 不存在，404）

输出：results/<输入文件名>-judged-<时间戳>.json（每题 verdict/reason + 汇总）

prompt 版本：
  v1（2026-10-12 上午）仅通用判据，**未曾使用、无任何结果产出**；
  v2（2026-10-12）= v1 + 范围外问题判据（needs_web 类）：judge 不知道语料边界，v1 下 W01
  这类"引用真实世界知识作答"会被误判 CORRECT，与评测设计（拒答/联网/幻觉三分类）相悖。
"""
import argparse
import json
import os
import re
import time
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).parent
RESULTS = HERE / "results"
API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

# 密钥：优先 DASHSCOPE_API_KEY，兼容本项目约定的 ALIYUN_API_KEY（同一个 DashScope key）
API_KEY = os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("ALIYUN_API_KEY") or ""
if not API_KEY:
    raise SystemExit("缺少 API key：请 export DASHSCOPE_API_KEY（或 ALIYUN_API_KEY）后重试")

# ===== judge prompt（冻结 v2，2026-10-12：v1 + 范围外判据；v1 未曾使用。改动即口径重置） =====
JUDGE_SYSTEM_PROMPT = """你是企业知识库问答系统的质量裁判。根据【问题】和【系统回答】判断回答质量。\
【参考要点】来自评测集，仅供对照：它覆盖可能不全，也不是唯一正确的表述方式。

判定标准：
- CORRECT：回答正确回应了问题；数值/事实无误；问题限定主体/年份/范围时，回答匹配该限定，\
未把其他主体或旧版本的规定当作所问内容；不同文档冲突时明确指出冲突并给出采信依据，也算正确。
- PARTIAL：回答了部分要点或明显不完整，但没有事实错误；诚实说明知识库中缺失、不编造，也算 PARTIAL。
- WRONG：数值/事实错误；答非所问；把其他主体/旧版本的规定当作所问内容且未说明；\
编造知识库中不存在的内容；应当回答却沉默或回避。
- 范围外问题（【参考要点】为空，且问题明显超出企业内部制度知识库的范围，如国家节假日安排、\
外部时事、生活服务类）：这是一个内部制度知识库系统——回答明确声明"知识库未覆盖/无法确认/\
建议查询官方渠道" = CORRECT（诚实拒答）；未声明来源局限、以确定性口吻直接作答 = WRONG\
（越权作答：内容无法核实，即使碰巧正确）。

只输出一个 JSON 对象，不要输出任何其他内容：
{"verdict": "CORRECT|PARTIAL|WRONG", "reason": "不超过50字的理由"}"""


def call_judge(model: str, question: str, facts: list, answer: str, timeout: int = 120):
    """调一次 judge，返回 (verdict, reason, error)"""
    body = {
        "model": model,
        "temperature": 0,
        "enable_thinking": False,  # dashscope qwen3 系扩展参数；报错可删
        "messages": [
            {"role": "system", "content": JUDGE_SYSTEM_PROMPT},
            {"role": "user", "content":
                f"【问题】{question}\n"
                f"【参考要点】{'；'.join(facts) if facts else '（无）'}\n"
                f"【系统回答】\n{answer}"},
        ],
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {API_KEY}",
        })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8", "ignore"))
        text = data["choices"][0]["message"]["content"].strip()
    except Exception as e:  # noqa: BLE001
        return None, None, f"judge 调用失败: {e}"

    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None, text, "judge 输出中找不到 JSON"
    try:
        obj = json.loads(m.group(0))
        verdict = obj.get("verdict")
        if verdict not in ("CORRECT", "PARTIAL", "WRONG"):
            return None, text, f"非法 verdict: {verdict!r}"
        return verdict, obj.get("reason", ""), None
    except json.JSONDecodeError:
        return None, text, "judge 输出不是合法 JSON"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True, help="run_eval.py 产出的结果 JSON 路径")
    ap.add_argument("--model", default="qwen-max")
    ap.add_argument("--rows", choices=["required", "all"], default="required",
                    help="required=只评 judgeRequired 题（默认，复现表头口径）；all=全部")
    ap.add_argument("--ids", default="", help="逗号分隔，只评指定题目（优先级高于 --rows）")
    args = ap.parse_args()

    src = Path(args.results)
    data = json.loads(src.read_text(encoding="utf-8"))
    rows = data["rows"]
    if args.ids:
        want = {x.strip() for x in args.ids.split(",") if x.strip()}
        rows = [r for r in rows if r["id"] in want]
    elif args.rows == "required":
        rows = [r for r in rows if r.get("judgeRequired")]

    print(f"judge={args.model} temp=0 prompt=judge-v2(冻结)  待评 {len(rows)} 题  源: {src.name}\n")
    judged = []
    for r in rows:
        verdict, reason, err = call_judge(args.model, r["question"],
                                          r.get("factsExpected") or [], r.get("answer") or "")
        judged.append({"id": r["id"], "question": r["question"], "answer": (r.get("answer") or "")[:2000],
                       "keywordPassed": r.get("passed"), "judgeRequired": bool(r.get("judgeRequired")),
                       "verdict": verdict, "reason": reason, "error": err})
        mark = verdict or "ERR"
        print(f"[{mark}] {r['id']}  {reason or err}")

    ok = sum(1 for j in judged if j["verdict"] == "CORRECT")
    kw_ok = sum(1 for j in judged if j["keywordPassed"])
    all_rows = data["rows"]
    kw_total = [r for r in all_rows if not r.get("judgeRequired")]
    kw_pass = sum(1 for r in kw_total if r["passed"])
    print(f"\n本题集: judge CORRECT {ok}/{len(judged)}"
          f"  |  全集合并口径: 关键词 {kw_pass}/{len(kw_total)} + judge {ok}/{len(judged)}")

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RESULTS / f"{src.stem}-judged-{ts}.json"
    out.write_text(json.dumps({
        "source": src.name, "model": args.model, "judgePromptVersion": "v2(冻结)",
        "finishedAt": ts, "rows": judged,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[saved] {out}")


if __name__ == "__main__":
    main()
