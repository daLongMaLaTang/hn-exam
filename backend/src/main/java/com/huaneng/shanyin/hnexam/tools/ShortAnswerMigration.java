package com.huaneng.shanyin.hnexam.tools;

import java.sql.*;
import java.util.*;

/**
 * Standalone additive migration. Does not bootstrap Spring/Hibernate or load application secrets.
 * Supply JDBC_URL, JDBC_USER, JDBC_PASSWORD in the process environment; run after a verified backup.
 */
public final class ShortAnswerMigration {
    private static final String VERSION="20261020_short_atomic_v2";
    private final Connection db;
    private final boolean mysql;
    private final String time;
    private ShortAnswerMigration(Connection db) throws SQLException {
        this.db=db;String product=db.getMetaData().getDatabaseProductName();
        if(!product.equals("MySQL") && !product.equals("H2")) throw new SQLException("Only MySQL and H2 are supported");
        mysql=product.equals("MySQL");time=mysql?"datetime(6)":"timestamp(6) with time zone";
    }
    public static void main(String[] args) throws Exception {
        var env=System.getenv();String url=env.get("JDBC_URL");
        if(url==null || !url.startsWith("jdbc:")) throw new IllegalArgumentException("Set JDBC_URL, JDBC_USER and JDBC_PASSWORD");
        try(var db=DriverManager.getConnection(url,env.getOrDefault("JDBC_USER",""),env.getOrDefault("JDBC_PASSWORD",""))) {migrate(db);}
        System.out.println(VERSION+" verified; no historical data was backfilled");
    }
    public static void migrate(Connection db) throws SQLException {
        new ShortAnswerMigration(db).run();
    }
    private void sql(String text) throws SQLException {try(var s=db.createStatement()){s.execute(text);}}
    private String table(String name) throws SQLException {
        try(var rs=db.getMetaData().getTables(db.getCatalog(),null,"%",new String[]{"TABLE","BASE TABLE"})) {
            while(rs.next()) if(name.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return rs.getString("TABLE_NAME");
        }
        return null;
    }
    private boolean column(String table,String column) throws SQLException {
        String actual=table(table);if(actual==null)return false;
        try(var rs=db.getMetaData().getColumns(db.getCatalog(),null,actual,"%")) {
            while(rs.next()) if(column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }
    private boolean index(String table,String name) throws SQLException {
        try(var rs=db.getMetaData().getIndexInfo(db.getCatalog(),null,table(table),false,false)) {
            while(rs.next()) {String actual=rs.getString("INDEX_NAME");if(actual!=null && actual.equalsIgnoreCase(name)) return true;}
        }
        return false;
    }
    private void record(String step) throws SQLException {
        try(var q=db.prepareStatement("select count(*) from hnexam_schema_change where version_key=? and step_key=?")) {
            q.setString(1,VERSION);q.setString(2,step);
            try(var rs=q.executeQuery()) {rs.next();if(rs.getInt(1)>0)return;}
        }
        try(var p=db.prepareStatement("insert into hnexam_schema_change(version_key,step_key,applied_at) values (?,?,CURRENT_TIMESTAMP)")) {
            p.setString(1,VERSION);p.setString(2,step);p.executeUpdate();
        }
    }
    private void add(String table,String name,String type) throws SQLException {
        if(!column(table,name)) sql("alter table "+table+" add column "+name+" "+type);
        record(table+"."+name);
    }
    private void create(String table,String fields) throws SQLException {
        if(table(table)==null) sql("create table "+table+" ("+fields+")"+(mysql?" ENGINE=InnoDB":""));
        record(table);
    }
    private void index(String table,String name,String fields,boolean unique) throws SQLException {
        if(!index(table,name)) sql("create "+(unique?"unique ":"")+"index "+name+" on "+table+" ("+fields+")");
        record(name);
    }
    private void run() throws SQLException {
        if(!db.getAutoCommit()) throw new SQLException("Migration needs its own autocommit connection");
        if(mysql) {
            try(var s=db.createStatement();var r=s.executeQuery("select GET_LOCK('hnexam_short_atomic_v2_migration',0)")) {
                if(!r.next() || r.getInt(1)!=1) throw new SQLException("Another migration is running");
            }
        } else sql("SET EXCLUSIVE 1");
        try {
            for(String[] required:List.of(new String[]{"short_answer_rubric","question_version","rubric_json"},
                    new String[]{"exam_question","snapshot_stem","snapshot_answer","points"},
                    new String[]{"personal_exam_question","snapshot_stem","snapshot_answer","points"},
                    new String[]{"exam_attempt_answer","answer_text","awarded_points"}))
                for(int i=1;i<required.length;i++) if(!column(required[0],required[i])) throw new SQLException("Missing prerequisite "+required[0]+"."+required[i]);
            sql("create table if not exists hnexam_schema_change (version_key varchar(80) not null,step_key varchar(120) not null,applied_at "+time+" not null,primary key(version_key,step_key))");
            for(String[] c:List.of(new String[]{"reuse_key","varchar(64)"},new String[]{"policy_version","varchar(255)"},new String[]{"normalization_version","varchar(255)"},
                    new String[]{"format_version","integer"},new String[]{"preparation_token","varchar(255)"},new String[]{"preparation_lease_until",time},
                    new String[]{"validated_at",time},new String[]{"preparation_error","varchar(1000)"})) add("short_answer_rubric",c[0],c[1]);
            index("short_answer_rubric","uk_short_rubric_reuse","reuse_key",true);
            for(String t:List.of("exam_question","personal_exam_question")) {
                add(t,"short_rubric_id","bigint");add(t,"short_scoring_config","longtext");add(t,"short_locked_at",time);
            }
            add("exam_question","short_snapshot_unreliable","boolean");
            add("exam_attempt_answer","short_scoring_policy_version","varchar(255)");
            add("exam_attempt_answer","short_decision_cache_id","bigint");
            String identity=mysql?"bigint not null auto_increment primary key":"bigint generated by default as identity primary key";
            create("short_answer_job","id "+identity+",exam_id bigint not null,kind varchar(20) not null,state varchar(20) not null,active_key varchar(100),user_id bigint,actor varchar(255),created_at "+time+",finished_at "+time);
            index("short_answer_job","uk_short_job_active","active_key",true);
            index("short_answer_job","idx_short_job_exam","exam_id,id",false);
            create("short_answer_job_item","id "+identity+",job_id bigint not null,target_key varchar(100) not null,kind varchar(20) not null,snapshot_id bigint,personal boolean not null default false,attempt_id bigint,question_id bigint,expected_revision bigint,answer_version varchar(64),state varchar(20) not null,attempts integer not null default 0,next_run_at "+time+",token varchar(255),lease_until "+time+",rubric_id bigint,config_version varchar(64),error_code varchar(40),error_message varchar(1000)");
            index("short_answer_job_item","uk_short_job_target","job_id,target_key",true);
            index("short_answer_job_item","idx_short_item_due","state,next_run_at",false);
            create("short_answer_decision_cache","id "+identity+",cache_key varchar(64) not null,rubric_id bigint,model_config varchar(64),answer_text longtext,decision_json longtext,model varchar(255),state varchar(20),token varchar(255),lease_until "+time+",created_at "+time+",invalidated_at "+time);
            index("short_answer_decision_cache","uk_short_decision_key","cache_key",true);
            record("COMPLETE");
        } finally {
            if(mysql) sql("select RELEASE_LOCK('hnexam_short_atomic_v2_migration')");else sql("SET EXCLUSIVE 0");
        }
    }
}
