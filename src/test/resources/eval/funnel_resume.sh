#!/usr/bin/env bash
# 检索漏斗实验：续跑脚本（2026-10-13 暂停于配置 B 单发/agent 两臂）
#
# 已完成（results/ 内）：
#   funnelA-baseline / funnelA-singleshot / funnelA-agent   配置 A：N=10 / K=5
#   funnelB-baseline                                        配置 B：N=20 / K=10
# 待跑：
#   funnelB-singleshot / funnelB-agent
#
# 用法：bash funnel_resume.sh
set -e
cd "$(dirname "$0")"
ROOT=../../../../..
IDS="MA01,MA02,MA03,AG01,AG02,AZ01,AZ02,AZ06,AZ07,AZ08,E01,E02,E03,E04,E05,E06,E07,E08,E09,E10"

. /tmp/aliyun_key.sh 2>/dev/null || true
: "${ALIYUN_API_KEY:?请先 export ALIYUN_API_KEY（或 source /tmp/aliyun_key.sh）}"
export ALIYUN_OPENSEARCH_API_KEY

echo "== 以配置 B（N=20/K=10）启动应用；devtools 热重启必须关闭（否则编译会清空向量库）=="
cd "$ROOT"
nohup ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev --server.port=8080 \
--spring.devtools.restart.enabled=false \
--rag.search.top-k=20 --rag.search.top-ratio=10 \
--rag.agent.search.top-k=20 --rag.agent.search.top-ratio=10" > /tmp/rag-app-deep.log 2>&1 &

cd - >/dev/null
for i in $(seq 1 30); do
  sleep 5
  curl -s -m 3 "http://localhost:8080/api/knowledge/search?query=x&topK=1" | grep -q success && break
done

echo "== KB 健康校验（必须非空，否则先跑 upload_corpus.py 重建）=="
curl -s -m 30 --get "http://localhost:8080/api/knowledge/search" \
  --data-urlencode "query=年假" --data "topK=5&topRatio=0" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); n=len(d.get('data') or []); print(f'  检索命中 {n} 条'); sys.exit(0 if n else 1)"

echo "== 续跑待完成的两臂 =="
python3 run_eval.py --label funnelB-singleshot --endpoint singleshot --ids "$IDS"
python3 run_eval.py --label funnelB-agent      --endpoint agent      --ids "$IDS"

echo "== 出报告 =="
python3 funnel_report.py
