# hqhexam.cn 宝塔服务器部署

2026-10-23 部署。当前线上采用宝塔已安装的 Nginx、MySQL 8、Redis，Java 17 后端由 systemd 管理。`../prod` 是另一套 Docker/Caddy 模板，不是本服务器的运行方式。

## 位置

- 域名：`https://hqhexam.cn`，服务器 `38.76.202.149`。
- 后端：`/opt/hnexam/current/app.jar`，当前链接指向 `releases/20261023-current`。
- 前端：`/www/wwwroot/hqhexam.cn`。
- 站点：已登记在宝塔网站列表，配置 `/www/server/panel/vhost/nginx/hqhexam.cn.conf`。
- 数据库：`hnexam_prod_20261023`，用户 `hnexam_prod`，仅授权此库；保留本机原库 `hn_exam`。
- 私有配置：`/opt/hnexam/config/hnexam.env`、`mysql-client.cnf`；均为 root 专用，勿输出、提交或公开下载。
- AI 主密钥：`/opt/hnexam/data/ai-master.key`，权限 `600`，所有者 `hnexam`；来自本机原密钥，不能重新生成替代。
- 初次迁移快照：`/opt/hnexam/backup/source-20261023.sql`；行数和完整表校验值全部核对一致。

## 运行与检查

```bash
systemctl status hnexam --no-pager
journalctl -u hnexam -n 100 --no-pager
curl --fail https://hqhexam.cn/api/health
/www/server/nginx/sbin/nginx -t
```

后端只监听 `127.0.0.1:8080`。`hnexam.service` 已启用开机启动及异常自动重启；MySQL、Redis、Nginx 也已检查为启用。没有通过真实重启服务器验收开机流程。

## 备份和证书

`hnexam-backup.timer` 每天北京时间 02:30 运行，日常数据库备份保留 14 天；初次迁移快照不在清理范围。即时备份：

```bash
systemctl start hnexam-backup.service
systemctl list-timers hnexam-backup.timer certbot.timer
```

SQL 压缩备份位于 `/opt/hnexam/backup/hnexam-*.sql.gz`。恢复时还需要私有配置与 AI 主密钥，不能仅恢复数据库后重新生成 AI 密钥。

HTTPS 使用 Certbot webroot，证书位于 `/etc/letsencrypt/live/hqhexam.cn/`，`certbot.timer` 自动续期，续期后的 Nginx 重载钩子位于 `/etc/letsencrypt/renewal-hooks/deploy/hnexam-nginx.sh`。不要再用宝塔生成另一份站点配置覆盖当前配置；如通过面板编辑，保留 API 转发、SPA 回退、ACME 校验路径和证书引用。

首次备份和北京时间 02:30 的定时备份已成功执行；证书续期 `certbot renew --dry-run` 已通过。服务器时区为 `Asia/Shanghai`，MySQL 全局时区为 `+08:00`，并已写入 `/etc/my.cnf` 持久配置。

相关官方说明：[Certbot webroot 与自动续期](https://eff-certbot.readthedocs.io/en/stable/using.html)、[Nginx HTTPS 配置](https://nginx.org/en/docs/http/configuring_https_servers.html)。

## 下一次更新

1. 在 `E:/hnExam` 用最新工作区源码构建并完成必要测试，不使用历史 JAR。
2. 没有进行中的考试时安排发布；先备份线上库。后续更新不重新导入本机库覆盖线上新记录。
3. 新 JAR 上传到新的 `/opt/hnexam/releases/<版本>/`，准备好对应前端产物；需要的数据库变更按增量迁移文档执行。
4. 切换 `current`，更新站点静态文件，重启 `hnexam`，检查健康和页面资源。

保持 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`，AI 后台开关为 `true`。非空答案只生成建议分，人工确认和成绩公布仍由原业务流程完成。答案同步和 10 秒上传缓冲按 `../ANSWER_SYNC.md` 执行。

本轮未确认或公布成绩，未执行百人负载验收。上线后以服务器库为后续正式数据源，本机旧后端已停止，原数据和迁移快照保留。
