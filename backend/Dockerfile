# Smartore 应用服务共享运行镜像。
#
# 为什么不在镜像内构建：本仓库是多模块 reactor（各 api 模块是内部依赖），容器内 mvn 构建
# 要重复下载整个依赖树且拿不到宿主 ~/.m2 缓存。约定与原有 dev.sh/systemd 一致——
# 宿主机预构建 jar，镜像只做运行时：
#   cd backend && mvn -q package -DskipTests
#   docker compose -f deploy/compose.apps.yaml up -d --build
FROM eclipse-temurin:21-jre

# 健康检查需要 curl（actuator/health），与 compose 的 healthcheck 对应
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 各服务在 compose 里以 --build-arg JAR_FILE=<模块 target 下的 jar 相对路径> 指定
ARG JAR_FILE
COPY ${JAR_FILE} app.jar

# 容器内存限制下按百分比分配堆，而不是默认最大堆（1/4 物理内存）；
# JAVA_OPTS 可由 compose 覆盖（如按服务调 Xmx）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
