package com.huaneng.shanyin.hnexam.tools;

import java.sql.*;
import java.util.Map;

/** Additive migration, run with a separate JDBC connection after backup and before app restart. */
public final class AnswerSyncMigration {
    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        try (var db = DriverManager.getConnection(env.get("JDBC_URL"), env.get("JDBC_USER"), env.get("JDBC_PASSWORD"))) {
            migrate(db);
        }
        System.out.println("20261022_answer_sync_v1 verified");
    }

    public static void migrate(Connection db) throws SQLException {
        if (!db.getAutoCommit()) throw new SQLException("Use a dedicated autocommit connection");
        boolean mysql = db.getMetaData().getDatabaseProductName().equals("MySQL");
        if (!mysql && !db.getMetaData().getDatabaseProductName().equals("H2")) throw new SQLException("Unsupported database");
        if (mysql) {
            try (var s = db.createStatement(); var r = s.executeQuery("select GET_LOCK('hnexam_answer_sync_migration',0)")) {
                if (!r.next() || r.getInt(1) != 1) throw new SQLException("Another migration is running");
            }
        }
        try {
            String table = null;
            try (var rows = db.getMetaData().getTables(db.getCatalog(), null, "%", new String[]{"TABLE", "BASE TABLE"})) {
                while (rows.next()) if ("exam_attempt".equalsIgnoreCase(rows.getString("TABLE_NAME"))) table = rows.getString("TABLE_NAME");
            }
            if (table == null) throw new SQLException("Missing exam_attempt; no database was initialized");
            for (var c : Map.of("answer_revision", "bigint not null default 0", "last_answer_request_id", "varchar(64)",
                    "last_answer_request_hash", "varchar(64)").entrySet()) {
                boolean exists = false;
                try (var rows = db.getMetaData().getColumns(db.getCatalog(), null, table, "%")) {
                    while (rows.next()) if (c.getKey().equalsIgnoreCase(rows.getString("COLUMN_NAME"))) exists = true;
                }
                if (!exists) try (var s = db.createStatement()) { s.execute("alter table exam_attempt add column " + c.getKey() + " " + c.getValue()); }
            }
            try (var s = db.createStatement()) {
                s.execute("create table if not exists hnexam_schema_change (version_key varchar(80) not null,step_key varchar(120) not null,applied_at "
                        + (mysql ? "datetime(6)" : "timestamp(6) with time zone") + " not null,primary key(version_key,step_key))");
                s.executeUpdate("insert into hnexam_schema_change(version_key,step_key,applied_at) select '20261022_answer_sync_v1','COMPLETE',CURRENT_TIMESTAMP "
                        + "where not exists (select 1 from hnexam_schema_change where version_key='20261022_answer_sync_v1' and step_key='COMPLETE')");
            }
        } finally {
            if (mysql) try (var s = db.createStatement()) { s.execute("select RELEASE_LOCK('hnexam_answer_sync_migration')"); }
        }
    }
}
