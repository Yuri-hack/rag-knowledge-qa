#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gold chunk 标注（v2 口径）：expectDocKeyword 定文档 + fact 定位 chunk，无 kw 的题人工锚定。

v1 教训：只用 expectedFacts 反查（如 "5天"/"3次"）会命中 40+ chunk（含废止版 B*、范围变体 C*、
同域干扰 E*），gold 失去判别力。v2 用题目自带的设计锚点 expectDocKeyword 锁定权威文档，
再在文档内用 fact 定位具体 chunk；无 kw 的 10 题（R01/R03/MT07/AZ01-02/AZ04-08）人工指定。

产出 retrieval-annotations.json。gold 口径变更 = 度量口径变更，需记入结论文档。
"""
import json
import re
import subprocess
from pathlib import Path

HERE = Path(__file__).parent


def norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def load_chunks():
    sql = ("SELECT document_id, file_name, chunk_index, content FROM document_chunk "
           "ORDER BY document_id, chunk_index;")
    out = subprocess.run(
        ["docker", "exec", "rag-mysql-dev", "mysql", "--default-character-set=utf8mb4",
         "-uroot", "-pdev_password", "rag_db", "-N", "-B", "-e", sql],
        capture_output=True, text=True)
    chunks = []
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) >= 4:
            chunks.append({"documentId": parts[0], "fileName": parts[1],
                           "chunkIndex": int(parts[2]), "content": parts[3]})
    return chunks


# 无 expectDocKeyword 的题：人工锚定（fileName 前缀 + 该文档内用于定位 chunk 的 needle）
MANUAL_DOCS = {
    # 对比/聚合题：gold 天然跨文档，kw 只锚住一边，必须人工补全
    "D01": [("C01-上海子公司年假", ["10 天"]), ("02-职级与假期对照表", ["8"])],
    "MA01": [("C01-上海子公司年假", ["10 天"]), ("C03-上海子公司病假", ["10 个工作日"]),
             ("02-职级与假期对照表", ["8 天"]), ("07-加班与调休", ["1:1.5"])],
    "MA02": [("C02-上海子公司考勤", ["20", "5"]), ("05-考勤管理", ["50", "3"])],
    "MA03": [("C13-驻场人员差旅", ["300"]), ("03-差旅费报销", ["600"])],
    "AG01": [("10-专项假期", ["婚假"]), ("01-年假管理制度", ["年假"]), ("05-考勤管理", ["病假"])],
    "AG02": [("03-差旅费报销", ["15 个工作日"]), ("04-紧急费用审批", ["3 个工作日"]),
             ("06-员工试用期", ["5 个工作日"])],
    "R01": [("02-职级与假期对照表", ["8"]), ("01-年假管理制度", ["法定节假日"])],
    "R03": [("04-紧急费用审批", ["5000"]), ("03-差旅费报销制度", ["绿色通道"])],
    "MT07": [("02-职级与假期对照表", ["P4"]), ("C01-上海子公司年假", ["7 天"])],
    "AZ01": [("02-职级与假期对照表", ["8"]), ("07-加班与调休", ["1:1.5"]),
             ("09-信息安全", ["90"]), ("08-远程办公", ["2 天"])],
    "AZ02": [("04-紧急费用审批", ["5000"]), ("06-员工试用期", ["5 个工作日"]),
             ("02-职级与假期对照表", ["P4"])],
    "AZ04": [("07-加班与调休", ["实报实销"]), ("14-加班餐费", ["说明单"])],
    "AZ05": [("10-专项假期", ["婚假 3 天"]), ("11-假期申请", ["结婚证"])],
    "AZ06": [("02-职级与假期对照表", ["P4"]), ("10-专项假期", ["婚假 3 天"]),
             ("07-加班与调休", ["1:3"]), ("05-考勤管理", ["补卡"]),
             ("08-远程办公", ["2 天"]), ("03-差旅费报销", ["450"])],
    "AZ07": [("06-员工试用期", ["5 个工作日"]), ("C14-驻场加班", ["1:1"]),
             ("C01-上海子公司年假", ["10 天"]), ("07-加班与调休", ["1:1.5"]),
             ("10-专项假期", ["3 天"]), ("03-差旅费报销", ["450"])],
    "AZ08": [("C01-上海子公司年假", ["10 天"]), ("C03-上海子公司病假", ["10 个工作日"]),
             ("02-职级与假期对照表", ["8"]), ("06-员工试用期", ["5 个工作日"])],
}


def chunks_of_doc(chunks, doc_prefix):
    return [c for c in chunks if c["fileName"].startswith(doc_prefix)]


def match_chunks(chunks, doc_prefix, needles):
    """文档内取含任一 needle 的 chunk；全部落空则回退整篇（并标记待复核）。"""
    doc_chunks = chunks_of_doc(chunks, doc_prefix)
    hit = [c for c in doc_chunks if any(norm(n) in norm(c["content"]) for n in needles)]
    return (hit, False) if hit else (doc_chunks, True)


def main():
    qa = json.loads((HERE / "qa.json").read_text(encoding="utf-8"))
    chunks = load_chunks()
    print(f"chunks={len(chunks)}")

    annotations, review = {}, []
    for q in qa["questions"]:
        qid = q["id"]
        query = (q.get("turns") or [q.get("question")])[-1]
        if not q.get("shouldRetrieve", True):
            annotations[qid] = {"query": query, "status": "excluded",
                                "reason": "shouldRetrieve=false", "goldDocs": [], "goldChunks": []}
            continue

        facts = q.get("expectedFacts") or []
        kw = q.get("expectDocKeyword")
        gold_docs, gold_chunks, fallback_docs, method = [], [], [], None

        if kw and qid not in MANUAL_DOCS:
            method = "kw+fact"
            matched = sorted({c["fileName"] for c in chunks if kw in c["fileName"]})
            canonical = [d for d in matched if d[0].isdigit()]
            # kw 同时命中正典与 B 废止变体时，只取正典（变体是干扰项，不是 gold）
            docs = canonical if canonical else matched
            for doc in docs:
                gold_docs.append(doc)
                hit, fell_back = match_chunks(chunks, doc, facts)
                gold_chunks.extend(hit)
                if fell_back:
                    fallback_docs.append(doc)
        else:
            method = "manual" if qid not in MANUAL_DOCS or not kw else "manual(kw+补全)"
            for doc_prefix, needles in MANUAL_DOCS.get(qid, []):
                matched_docs = sorted({c["fileName"] for c in chunks_of_doc(chunks, doc_prefix)})
                if not matched_docs:
                    fallback_docs.append(doc_prefix)
                    continue
                gold_docs.extend(matched_docs)
                hit, fell_back = match_chunks(chunks, doc_prefix, needles or facts)
                gold_chunks.extend(hit)
                if fell_back:
                    fallback_docs.append(doc_prefix)

        seen, uniq = set(), []
        for c in gold_chunks:
            key = (c["documentId"], c["chunkIndex"])
            if key not in seen:
                seen.add(key)
                uniq.append(c)

        confidence = "high" if uniq and not fallback_docs else "needs-review"
        annotations[qid] = {
            "query": query, "category": q["category"], "method": method,
            "confidence": confidence,
            "goldDocs": sorted(set(gold_docs)),
            "goldChunks": [{"documentId": c["documentId"], "fileName": c["fileName"],
                            "chunkIndex": c["chunkIndex"]} for c in uniq],
        }
        if confidence != "high":
            review.append((qid, fallback_docs, len(uniq)))

    out = HERE / "retrieval-annotations.json"
    out.write_text(json.dumps({
        "generatedAt": "2026-10-13", "version": "v2(kw+fact / manual)",
        "chunkCount": len(chunks),
        "note": "gold 文档由 expectDocKeyword 锚定（30 题），无 kw 的 10 题人工指定；文档内按 fact 定位 chunk。",
        "questions": annotations,
    }, ensure_ascii=False, indent=1), encoding="utf-8")

    from collections import Counter
    print("置信度:", dict(Counter(v.get("confidence") or v.get("status") for v in annotations.values())))
    print("gold chunk 数分布:", dict(Counter(len(v["goldChunks"]) for v in annotations.values())))
    if review:
        print("\n需复核:")
        for qid, fb, n in review:
            print(f"  {qid}: 回退文档={fb} goldChunks={n}")
    print(f"\n[saved] {out}")


if __name__ == "__main__":
    main()
