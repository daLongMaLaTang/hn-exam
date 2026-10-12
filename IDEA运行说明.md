> 2026-10-09 更新：本地后端已切换为 MySQL（127.0.0.1:3306/hn_exam），与 IDEA 的 hn_exam@127.0.0.1 使用同一份数据。H2 仅保留作历史备份；下文 H2 连接说明属于切换前记录。测试继续使用隔离内存库。

﻿# IDEA 本地开发与数据库连接

配置时间：2026-10-08。用 IDEA 打开整个 `E:\hnExam` 目录。

## 已配置的连接

打开“视图 → 工具窗口 → 数据库（Database）”，查看以下两个数据源。如果暂时没有显示，先保存代码，再重新打开项目；自定义驱动未加载时重启 IDEA。

| 名称 | 本地地址 | 用户 | 密码 |
| --- | --- | --- | --- |
| 考试系统 · 当前H2数据库 | `E:\hnExam\backend\data\hnexam.mv.db` | `sa` | 留空 |
| 考试系统 · Redis缓存 | `127.0.0.1:6379`，数据库 `0` | 自动使用 `default` | 自动读取本机 `.env.local` |

Redis 已配置为自动读取本机 `.env.local` 中的 `HNEXAM_REDIS_PASSWORD`，不需要回忆或填写密码。IDEA 中认证方式为“无身份验证”，表示由本地连接驱动代为提供现有凭据，并未关闭 Redis 服务端密码验证。保存代码并重启 IDEA 后，打开“考试系统 · Redis缓存”，点击“测试连接”或展开数据源。

H2 完整连接地址：

```text
jdbc:h2:file:E:/hnExam/backend/data/hnexam;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE;IFEXISTS=TRUE
```

H2 已启用本地共享连接，后台运行期间也可以查看数据。`IFEXISTS=TRUE` 可以防止路径填写错误时创建另一份空数据库。数据库驱动版本与后台一致，为 2.3.232；Redis 驱动为 1.6，驱动文件在 `.local/idea-drivers`。驱动注册在本机 IDEA 设置中，换电脑需重新配置。

当前使用 H2，不是 MySQL。不要直接切换到 MySQL，否则不会自动带上当前用户、题库和考试数据。数据库工具在后台运行时建议只查询，避免手动修改正在使用的考试数据。

## 启动系统

1. 项目 SDK 为 Java 17：`E:\web\jdk17\jdk-17.0.20.1+1`。
2. 后端 Maven 项目为 `backend/pom.xml`。本机 Maven 在 `D:\web\maven`，依赖仓库在 `D:\web\maven-repo`。
3. 前端使用 `D:\web\nodejs\node.exe`，依赖已经安装。
4. IDEA 顶部选择“本地完整系统”，启动前端和后台。也可以分别选择“后端 Backend”“前端 Frontend”。前端启动配置会准备 Docker/Redis。
5. 访问 <http://127.0.0.1:5173>。

后端工作目录固定为 `E:\hnExam\backend`，防止打开其他位置的数据库。后端、前端分别使用 8080 和 5173 端口；已有服务运行时不要重复启动。当前服务已在后台运行，IDEA 的停止按钮不能停止由外部脚本启动的服务。

沿用现有账号和已修改的密码。本次没有重置账号密码，没有迁移或清空数据库。AI 相关功能仍取决于是否配置了对应服务和密钥，不属于 H2/Redis 连接。

## 连接检查

IDEA 中可选择“连接检查”运行配置；也可以在项目终端执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\check-idea-connections.ps1
```

检查仅查询数据库、向 Redis 发 PING、读取后台健康状态。也可使用 `deploy/本地数据库只读检查.sql` 和 `deploy/本地服务检查.http`。

2026-10-08 实测：后台运行时另一个 Java 进程成功读取同一份 H2 数据库（115 个用户、655 道题）；Redis 返回 PONG；后台与 Redis 健康状态均为 UP；前端返回 HTTP 200。

IDEA 窗口读取连续超时，因此本次通过配置文件完成设置，并用独立连接验证服务；尚未在 IDEA 界面内点击“测试连接”。自动读取密码的连接驱动已使用 IDEA 自带 Java 实测认证成功，返回 Redis 7.4.11。

修改前的配置与重启前的数据库备份位于 `E:\exam\2026-10-07\jixu-2\work\idea-before`。

## Redis 自动读取密码的维护说明

本机附加连接驱动位于 .local/idea-drivers/hnexam-local-redis.jar，源码在同目录 src 中；它仅接受 127.0.0.1:6379 的数据库 0，防止本机密码被用于其他地址。原始 Redis 驱动要求 Java 21 或更高，使用 IDEA 自带 Java 运行，不影响后台的 Java 17。移动项目或修改 Redis 地址时需更新该本地驱动。密码继续由 .env.local 统一管理。

## 连接能看到但没有表

2026-10-08 已核实当前 H2 的 public 下有 16 张业务表（包括 sys_user、qb_question、exam）。IDEA 自动导入的 hnexam 原本采用相对路径，已将其改为与“考试系统 · 当前H2数据库”相同的完整路径，避免查看另一份数据文件。

在 IDEA 数据库面板中选中“考试系统 · 当前H2数据库”，按 Ctrl+F5 刷新。若仍不显示 public，右键该连接 → 工具（Tools）→ 管理显示的架构（Manage Shown Schemas），勾选 public 并确认，再刷新。展开 public → 表（Tables）。若提示连接错误，请保留完整错误信息，勿新建或删除数据库。

Redis 显示的是缓存键，不是业务表。hn_exam@127.0.0.1 为备用 MySQL 配置，当前应用没有使用它。用户、题库和考试表应在 H2 中查看。

仍未完成的验证：IDEA 原生窗口捕获报 FrameArrived timed out，无法确认界面刷新后的结果。以上表数量由独立 JDBC 查询确认，并非在 IDEA 数据库面板中看到。

官方排查说明：https://www.jetbrains.com/help/idea/cannot-find-a-database-object-in-the-database-tree-view.html

## DeepSeek 页面接入（2026-10-10）

运行更新后的项目，使用 admin 进入“成绩与人工判卷”→“接入 AI · DeepSeek”，仅填写 API Key 并点击“验证并接入”。成功后立即生效，无需重启。只有 admin 可查看和修改配置，其他 SYSTEM_ADMIN 账号也不能配置。密钥加密保存在 MySQL，主密钥文件由首次成功保存自动生成；当前本地路径为 E:/hnExam/.local/ai-master.key，迁移时须与数据库一同妥善备份。未填入真实密钥前仍未启用 AI。
