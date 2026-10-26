# 考试答案保存与交卷（R01–R03）

更新：2026-10-23。

## 行为与接口

- 答案编辑后防抖 700ms 保存，连续编辑最长等待 2500ms，最后 10 秒缩短为 100ms。仅存在待同步内容或未确认请求时重试。
- 页面在考试截止时锁定输入；服务器提供 10 秒最终上传缓冲。`app.exams.submission-grace-seconds` 当前为 10，与考生页说明保持一致。管理员关闭考试仍立即收卷。
- 服务器使用收到请求时的时间判断上传窗口；答卷已经收卷则不会重新写入或重新评分。数据库排队不保证先于已完成的收卷事务。
- AttemptView 增加 `answerRevision`、`submissionDeadline`。保存和交卷请求必须带 `expectedRevision`、`requestId`；旧页面缺失字段返回 400 并提示刷新。
- 在同一答卷的数据库锁内检查版本。旧版本返回 HTTP 409 / `ANSWER_CONFLICT`，页面保留本地内容并展示差异，用户选择后才能继续。
- 最后一次请求 ID 与内容哈希持久化；相同请求重试不增加版本，复用 ID 修改内容会冲突。更早请求在后续写入后重放会冲突，不覆盖新答案。
- 保存返回 `SaveAck`，交卷返回 `SubmissionAck`，成绩位于 `grade`。必须检查 `answersAccepted`，不能把 HTTP 成功直接理解为最新本地内容已计入。
- 保存请求 4 秒、交卷 5 秒、恢复/核对请求 5 秒超时，包含响应体读取。超时意味着结果待确认，不意味着服务器没有执行；重试保留原始请求 ID/内容。
- 草稿按标签页隔离，另有浏览器本地备份。重新打开时恢复最近备份，存在不同答案先核对。缓冲后未被接收的内容保留并提供下载，不自动跳转清空。

## 部署顺序

1. 确认没有进行中的真实考试，停止旧服务写入，备份数据库并恢复到独立库验证。
2. 构建前后端；在安全进程环境中设置 `JDBC_URL`、`JDBC_USER`、`JDBC_PASSWORD`，执行 `com.huaneng.shanyin.hnexam.tools.AnswerSyncMigration`（classpath 为编译类目录与 JDBC 驱动）。工具不加载 Spring 或配置文件，不初始化数据库。
3. 工具在 `exam_attempt` 增加 `answer_revision bigint not null default 0`、`last_answer_request_id varchar(64)`、`last_answer_request_hash varchar(64)`；写入 `hnexam_schema_change/20261022_answer_sync_v1/COMPLETE`。可重复执行，MySQL DDL 非整体事务，失败后修复再运行，不删除列回退。
4. 保持 `spring.jpa.hibernate.ddl-auto=validate`，同时更新前后端。旧页面应保留草稿并刷新，不能混用旧写入接口。
5. 核验健康、历史答卷及评分。浏览器故障测试使用隔离测试夹具，不在真实考生答卷上制造断网或过期。

## 本机已完成部署

- 2026-10-23 01:48（北京时间）核验：8080 `/api/health` 为 UP、Redis UP，5173 HTTP 200；生产库 `hn_exam`。
- 发布包 `.local/releases/answer-sync-20261023-0150.jar`；SHA256 `72785B0434AF563C8F8531D28B2CB992B10B99BED09E4EDEA9D3FA3F42565F0A`。
- 后端 Java17，工作目录 `E:/hnExam/backend`；前端 Vite 5173。日志 `logs/backend-answer-sync-20261023-0150.log`、`logs/frontend-answer-sync-20261023-0150.log` 及对应错误日志。
- 备份 `.local/backups/before-answer-sync-20261023-0147.sql` 已恢复到独立库 `hn_exam_restore_answer_sync_20261023`，29 张表校验和一致。恢复需显式 UTF-8 与 binary-mode，避免 Windows 客户端编码误读。
- 迁移后全部旧答卷字段变化数 0；其余 27 张表校验和不变，仅答卷表新增字段和迁移记录表发生预期变化。
- 服务启动后答案与评分表、AI 任务项校验和仍与备份一致；考试 #102 保持 52 份 SUBMITTED、35 份 SUGGESTED 且最终分为空、68 份简答空答自动 0。没有确认或公布成绩。
- 完整后端：190 项中 189 通过、1 跳过；隔离 MySQL：55 项通过；前端：12 项通过，类型检查和生产构建通过。未执行百人负载验收。
- 浏览器实测版本冲突及选择、最后一秒上传、响应丢失幂等重试、挂起后恢复、超时草稿下载、重载与关闭重开恢复，以及 1440/390/320 宽度和原有键盘限制。

此处记录是当时运行状态；下次操作需重新核验端口和进程。不要运行早期自动采用 AI 最终分的旧包。R04 及以后审查项不在本次范围。
