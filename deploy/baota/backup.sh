#!/usr/bin/env bash
set -euo pipefail
umask 077
backup_dir=/opt/hnexam/backup
mkdir -p "$backup_dir"
out="$backup_dir/hnexam-$(date +%Y%m%d-%H%M%S).sql.gz"
trap 'rm -f "${out}.tmp"' EXIT
/www/server/mysql/bin/mysqldump --defaults-extra-file=/opt/hnexam/config/mysql-client.cnf \
    --single-transaction --quick --routines --triggers --events --hex-blob \
    --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 \
    --default-character-set=utf8mb4 hnexam_prod_20261023 | gzip > "${out}.tmp"
gzip -t "${out}.tmp"
mv "${out}.tmp" "$out"
find "$backup_dir" -maxdepth 1 -type f -name 'hnexam-*.sql.gz' -mtime +14 -delete
printf 'Backup saved: %s\n' "$out"
