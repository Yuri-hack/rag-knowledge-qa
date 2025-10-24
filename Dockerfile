FROM openjdk:17-jdk-slim

# 安装curl用于健康检查
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# 创建工作目录
WORKDIR /app

# 复制JAR文件
COPY rag-knowledge-qa-*.jar app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/healthy/check || exit 1

# 暴露端口
EXPOSE 8080

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]