#!/bin/bash

set -e

echo "🚀 开始部署 RAG 知识库系统..."

# 配置变量
REMOTE_USER="root"
REMOTE_HOST="115.190.202.146"
REMOTE_DIR="/opt/rag-knowledge-qa"
LOCAL_DOCKER_COMPOSE_FILE="docker/prod/docker-compose.prod.yml"
REMOTE_DOCKER_COMPOSE_FILE="docker-compose.prod.yml"

# 1. 构建应用
echo "📦 构建应用..."
mvn clean package -DskipTests

# 2. 上传文件到服务器
echo "📤 上传文件到服务器..."
ssh ${REMOTE_USER}@${REMOTE_HOST} "mkdir -p ${REMOTE_DIR}"
scp -r \
  Dockerfile \
  ${LOCAL_DOCKER_COMPOSE_FILE} \
  target/*.jar \
  ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/

# 3. 在服务器上启动服务
echo "🐳 在服务器上启动 Docker 服务..."
ssh ${REMOTE_USER}@${REMOTE_HOST} "
  cd ${REMOTE_DIR}
  export \$(cat .env.prod | xargs)
  docker-compose -f ${REMOTE_DOCKER_COMPOSE_FILE} down
  docker-compose -f ${REMOTE_DOCKER_COMPOSE_FILE} up -d --build
"

# 4. 等待服务启动
echo "⏳ 等待 Milvus 和 Redis 服务启动..."
sleep 60

# 5. 检查服务状态
echo "🔍 检查服务状态..."
ssh ${REMOTE_USER}@${REMOTE_HOST} "
  cd ${REMOTE_DIR}
  docker-compose -f ${REMOTE_DOCKER_COMPOSE_FILE} ps
  docker-compose -f ${REMOTE_DOCKER_COMPOSE_FILE} logs --tail=20 app
"

echo "✅ 部署完成!"
echo "🌐 应用地址: http://${REMOTE_HOST}:8080"
