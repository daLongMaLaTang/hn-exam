# 后端运行时镜像：只负责跑已经打好的 jar，不在服务器上编译
# 4G 机器跑 Maven + MySQL 会直接 OOM，所以 jar 在本机 build.sh 里打好再上传
FROM eclipse-temurin:17-jre-jammy

# curl 用于容器健康检查和线上排障
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx1024m -XX:+UseSerialGC -Dfile.encoding=UTF-8"

WORKDIR /app

COPY app.jar /app/app.jar

# 日志与 AI 主密钥落盘目录，由 docker-compose 挂载持久化
RUN mkdir -p /app/logs /app/.local

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
