-- IDEA Database：选择“考试系统 · 当前H2数据库”后执行。
-- 只读检查，确认连接的是正在使用的数据库。
SELECT DATABASE_PATH() AS database_path, H2VERSION() AS h2_version;
SELECT COUNT(*) AS user_count FROM sys_user;
SELECT COUNT(*) AS question_count FROM qb_question;
SELECT COUNT(*) AS exam_count FROM exam;
SELECT COUNT(*) AS attempt_count FROM exam_attempt;
