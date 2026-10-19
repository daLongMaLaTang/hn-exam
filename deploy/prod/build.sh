#!/usr/bin/env bash
# 在本机构建生产产物，不要在服务器上跑（4G 内存跑 Maven 会 OOM）
# 用法：bash deploy/prod/build.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

echo "==> 1/3 打包后端 jar"
cd "$ROOT/backend"
mvn -DskipTests clean package ${MAVEN_ARGS:-}
JAR="$(ls target/hn-exam-*-SNAPSHOT.jar | head -n 1)"
cp "$JAR" "$HERE/app.jar"
echo "    已生成 app.jar（$(du -h "$HERE/app.jar" | cut -f1)）"

echo "==> 2/3 构建前端"
cd "$ROOT/frontend"
npm run build

echo "==> 3/3 复制静态文件到 www/"
rm -rf "$HERE/www"
mkdir -p "$HERE/www"
cp -r dist/. "$HERE/www/"

echo "完成。下一步：把 deploy/prod 整个目录上传到服务器，配好 .env 后 docker compose up -d --build"
