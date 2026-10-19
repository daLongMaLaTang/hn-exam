#!/usr/bin/env bash
# 日常运维脚本，在服务器上 cd 到本目录后执行
#   bash ops.sh status    查看容器状态
#   bash ops.sh health    接口连通性自检
#   bash ops.sh logs      跟踪后端日志
#   bash ops.sh backup    备份数据库到 backup/
#   bash ops.sh restore <文件.sql.gz>  从备份恢复
#   bash ops.sh update    重新构建并滚动重启后端
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "缺少 .env，先 cp .env.example .env 并填写"; exit 1; }
# shellcheck disable=SC1091
set -a; . ./.env; set +a

case "${1:-status}" in
  status)
    docker compose ps
    echo
    df -h / | tail -n 1
    echo "内存："
    free -h | head -n 2
    ;;

  health)
    echo -n "后端 /api : "
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1:8080/api/auth/login \
      -H 'Content-Type: application/json' -d '{"employeeNo":"__probe__","password":"__probe__"}' || echo 000)
    case "$code" in
      200|400|401|403) echo "正常 (HTTP $code)" ;;
      000) echo "无响应，容器可能没起来" ; exit 1 ;;
      *) echo "异常 (HTTP $code)，查 docker compose logs app" ; exit 1 ;;
    esac

    echo -n "MySQL     : "
    docker compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null | tail -n 1

    echo -n "Redis     : "
    docker compose exec -T redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping 2>/dev/null

    echo -n "HTTPS     : "
    curl -s -o /dev/null -w "HTTP %{http_code}\n" "https://$SITE_DOMAIN/" || echo "不可达（检查域名解析与证书）"
    ;;

  logs)
    docker compose logs -f --tail=200 app
    ;;

  backup)
    mkdir -p backup
    out="backup/hnexam-$(date +%Y%m%d-%H%M%S).sql.gz"
    docker compose exec -T mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
      --single-transaction --quick --default-character-set=utf8mb4 "$MYSQL_DATABASE" \
      | gzip > "$out"
    echo "已备份：$out ($(du -h "$out" | cut -f1))"
    # 只保留最近 14 份
    ls -1t backup/hnexam-*.sql.gz | tail -n +15 | xargs -r rm -f
    ;;

  restore)
    file="${2:-}"
    [ -f "$file" ] || { echo "用法：bash ops.sh restore backup/hnexam-xxx.sql.gz"; exit 1; }
    echo "即将覆盖数据库 $MYSQL_DATABASE，10 秒后开始，Ctrl+C 取消"
    sleep 10
    gunzip -c "$file" | docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"
    echo "恢复完成，建议 docker compose restart app"
    ;;

  update)
    docker compose up -d --build app
    docker compose logs --tail=50 app
    ;;

  *)
    echo "未知命令：$1"
    grep '^#' "$0" | head -n 9
    exit 1
    ;;
esac
