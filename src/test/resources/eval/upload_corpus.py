#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""语料全量重建：清空 MySQL 分块表 → 重启应用（Milvus collection recreate）→ 上传 100 篇 → 回填登记册。

前置：
  1. 应用已停止（脚本会自行以 recreate 参数重启）
  2. 环境变量 ALIYUN_API_KEY / ALIYUN_OPENSEARCH_API_KEY 可用
用法：
  python3 upload_corpus.py                 # 全量重建
  python3 upload_corpus.py --skip-truncate # 只补传（不推荐，易产生重复文档）

为什么需要脚本：语料重建已发生 4 次（每次 splitter/schema 变更后），手工流程易漏步骤。
"""
import argparse
import json
import os
import subprocess
import time
import urllib.request
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).parent
CORPUS = HERE / "corpus"
BASE = "http://localhost:8080"
MILVUS_PORT = os.environ.get("MILVUS_PORT", "19530")
UPLOAD_WORKERS = int(os.environ.get("UPLOAD_WORKERS", "4"))  # 可指向另一实例（如升级验证用 19531）
DB = ["docker", "exec", "rag-mysql-dev", "mysql", "-uroot", "-pdev_password", "rag_db", "-e"]


def corpus_files():
    files = (sorted(CORPUS.glob("[0-9][0-9]-*.md"))
             + sorted(CORPUS.glob("gen/split/*.md"))
             + sorted(CORPUS.glob("gen/batch3/*.md"))
             + sorted(CORPUS.glob("gen/batch5/*.md"))
             + sorted(CORPUS.glob("gen/batch6/*.md"))   # 多跳语料（配发通知+保修登记表）：此前漏收集，导致 MH 目标文档缺失
             )
    return files


def wait_app(timeout_s=180):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/api/knowledge/search?query=x&topK=1", timeout=5):
                return True
        except Exception:
            time.sleep(3)
    return False


def restart_app():
    subprocess.run(["pkill", "-f", "spring-boot:run"], capture_output=True)
    time.sleep(3)
    subprocess.run(["pkill", "-f", "rag-knowledge-qa"], capture_output=True)
    time.sleep(2)
    log = open("/tmp/rag-app-rebuild.log", "w")
    subprocess.Popen(
        ["./mvnw", "-q", "spring-boot:run",
         "-Dspring-boot.run.arguments=--spring.profiles.active=dev --server.port=8080 "
         "--spring.devtools.restart.enabled=false --milvus.port=%s --milvus.collection.recreate=true "
         "--rag.vector.flush-on-insert=false" % MILVUS_PORT],
        cwd=HERE.parents[3], stdout=log, stderr=log)
    if not wait_app():
        raise SystemExit("应用启动超时，检查 /tmp/rag-app-rebuild.log")


def truncate_db():
    subprocess.run(DB + ["SET FOREIGN_KEY_CHECKS=0; TRUNCATE document_chunk; "
                         "TRUNCATE uploaded_document; SET FOREIGN_KEY_CHECKS=1;"],
                   capture_output=True, text=True)


def upload_one(p):
    """单篇上传；返回 (fileName, documentId, error)。"""
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"fileName\"\r\n\r\n{p.stem}\r\n").encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n"
             f"corpus-rebuild\r\n").encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{p.name}\"\r\n"
             f"Content-Type: text/markdown\r\n\r\n").encode() + p.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/api/knowledge/upload", data=body,
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            r = json.loads(resp.read().decode("utf-8", "ignore"))
        if r.get("success"):
            return (p.stem, r.get("data"), None)
        return (p.stem, None, p.name)          # API 明确失败
    except Exception as e:  # noqa: BLE001
        return (p.stem, None, f"{p.name}: {str(e)[:60]}")


def upload(files, workers=None):
    """并发上传：每篇含 分句+逐块 embedding+Milvus 插入+flush，串行约 10s/篇；
    并发后受 Embedding API 与 Milvus flush 影响，实测可缩短数倍。"""
    workers = workers or UPLOAD_WORKERS
    registry, fails, done = [], [], 0
    lock = threading.Lock()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for stem, doc_id, err in pool.map(upload_one, files):
            with lock:
                done += 1
                if err:
                    fails.append(err)
                else:
                    registry.append((stem, doc_id))
                if done % 20 == 0:
                    print(f"  {done}/{len(files)}")
    return registry, fails


def verify(expected_docs):
    """校验入库结果：MySQL 分块数 + Milvus 可检索。返回 (ok, 说明)。"""
    out = subprocess.run(["docker", "exec", "rag-mysql-dev", "mysql", "-uroot", "-pdev_password",
                          "rag_db", "-N", "-B", "-e", "SELECT COUNT(*) FROM document_chunk;"],
                         capture_output=True, text=True)
    chunks = out.stdout.strip().splitlines()[-1] if out.stdout.strip() else "0"
    # Milvus 校验：搜一个必然存在的问题
    import urllib.parse
    qs = urllib.parse.urlencode({"query": "年假", "topK": 5, "topRatio": 0.0})
    try:
        with urllib.request.urlopen(BASE + "/api/knowledge/search?" + qs, timeout=60) as resp:
            got = len(json.loads(resp.read().decode("utf-8", "ignore")).get("data") or [])
    except Exception as e:  # noqa: BLE001
        return False, f"检索校验失败: {e}"
    if got == 0:
        return False, f"Milvus 检索返回 0 条（分块数={chunks}）——集合为空，上传可能未落到向量库"
    return True, f"MySQL 分块={chunks}, Milvus 检索命中={got} 条"


def restart_app_clean():
    """收尾：以 recreate=false 重启，避免长期驻留的实例在后续编译热重启时清空集合。"""
    subprocess.run(["pkill", "-f", "spring-boot:run"], capture_output=True)
    time.sleep(3)
    subprocess.run(["pkill", "-f", "rag-knowledge-qa"], capture_output=True)
    time.sleep(2)
    log = open("/tmp/rag-app.log", "w")
    subprocess.Popen(
        ["./mvnw", "-q", "spring-boot:run",
         "-Dspring-boot.run.arguments=--spring.profiles.active=dev --server.port=8080 "
         "--spring.devtools.restart.enabled=false --milvus.port=%s" % MILVUS_PORT],
        cwd=HERE.parents[3], stdout=log, stderr=log)
    if not wait_app():
        raise SystemExit("收尾重启超时")


def write_registry(registry):
    out = CORPUS / "gen/uploaded-ids.md"
    with out.open("w") as w:
        w.write("# 当前知识库 documentId 登记（全量重建后）\n\n| 文件 | documentId |\n|---|---|\n")
        for name, doc_id in registry:
            w.write(f"| {name} | {doc_id} |\n")
    print(f"[registry] {out} ({len(registry)} docs)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-truncate", action="store_true")
    ap.add_argument("--skip-restart", action="store_true")
    args = ap.parse_args()

    files = corpus_files()
    print(f"语料文件 {len(files)} 篇")
    if not args.skip_restart:
        print("[1/5] 重启应用（collection recreate）")
        restart_app()
    if not args.skip_truncate:
        print("[2/5] 清空 MySQL 分块表")
        truncate_db()
    print("[3/5] 上传")
    registry, fails = upload(files)
    # 批量入库期间关闭了逐篇 flush：收尾显式 flush 一次，确保 growing segment 落盘（耐久性等价）
    try:
        req = urllib.request.Request(
            "http://localhost:%s/v2/vectordb/collections/flush" % MILVUS_PORT,
            data=json.dumps({"collectionName": "knowledge_base", "dbName": "default"}).encode(),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=180) as resp:
            print("  收尾 flush:", resp.read().decode()[:120])
    except Exception as e:  # noqa: BLE001
        print("  收尾 flush 失败（不影响检索，但耐久性需留意）:", str(e)[:80])
    if fails:
        print("失败清单:", fails)
        raise SystemExit(f"有 {len(fails)} 篇上传失败，拒绝继续（KB 不完整）")

    print("[4/5] 校验入库（MySQL 分块 + Milvus 可检索）")
    ok, msg = verify(len(files))
    print(f"       {msg}")
    if not ok:
        raise SystemExit("入库校验失败：KB 为空或不可检索")

    # 收尾必须以 recreate=false 重启：带 recreate 的实例一旦被 DevTools 热重启就会再次清库
    print("[5/5] recreate=false 收尾重启 + 登记 documentId")
    restart_app_clean()
    write_registry(registry)
    print(f"完成：成功 {len(registry)} 篇")


if __name__ == "__main__":
    main()
