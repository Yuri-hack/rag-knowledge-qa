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
ROOT=../../../..
IDS="MA01,MA02,MA03,AG01,AG02,AZ01,AZ02,AZ06,AZ07,AZ08,E01,E02,E03,E04,E05,E06,E07,E08,E09,E10"

. /tmp/aliyun_key.sh 2>/dev/null || true
: "${ALIYUN_API_KEY:?请先 export ALIYUN_API_KEY（或 source /tmp/aliyun_key.sh）}"
# 必须显式 export：key 文件若只赋值不 export，子进程（应用）会拿到空 key → embedding 401 → 检索静默返回空
export ALIYUN_API_KEY ALIYUN_OPENSEARCH_API_KEY

echo "== 以配置 B（N=20/K=10）启动应用；devtools 热重启必须关闭（否则编译会清空向量库）=="
# 必须先停掉已有实例：否则 8080 被占，新实例静默启动失败，评测会跑在旧配置上（2026-10-13 踩过）
pkill -f "spring-boot:run" || true; sleep 3; pkill -f rag-knowledge-qa || true; sleep 2
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

echo "== 校验生效配置（防止跑在旧参数上）=="
ps -ef | grep "[s]pring-boot.run.arguments" | grep -q "rag.search.top-k=20" \
  && echo "  配置 B 参数已生效（N=20/K=10）" \
  || { echo "  !! 未检测到配置 B 参数，终止"; exit 1; }

echo "== KB 健康校验（必须非空，否则先跑 upload_corpus.py 重建）=="
curl -s -m 30 --get "http://localhost:8080/api/knowledge/search" \
  --data-urlencode "query=年假" --data "topK=5&topRatio=0" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); n=len(d.get('data') or []); print(f'  检索命中 {n} 条'); sys.exit(0 if n else 1)"

echo "== 续跑待完成的两臂（caffeinate 防止机器睡眠打断长跑）=="
# 2026-10-13 教训：机器睡眠会把 JVM 冻住，评测客户端挂在 SSE 上，白白浪费一轮
if command -v caffeinate >/dev/null 2>&1; then
  CAFF="caffeinate -i"
else
  CAFF=""
fi
$CAFF python3 run_eval.py --label funnelB-singleshot --endpoint singleshot --ids "$IDS"
$CAFF python3 run_eval.py --label funnelB-agent      --endpoint agent      --ids "$IDS"

echo "== 出报告 =="
python3 funnel_report.py
