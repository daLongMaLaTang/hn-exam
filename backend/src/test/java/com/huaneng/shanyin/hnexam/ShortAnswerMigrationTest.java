package com.huaneng.shanyin.hnexam;
import com.huaneng.shanyin.hnexam.tools.ShortAnswerMigration;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
class ShortAnswerMigrationTest {
    @Test void h2AdditiveMigrationCanResumeAndRepeatWithoutChangingOldRows() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:migration_short;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","")) {
            try(var s=c.createStatement()) {
                s.execute("create table short_answer_rubric(id bigint primary key,question_version varchar(64),rubric_json longtext)");
                s.execute("create table exam_question(id bigint primary key,snapshot_stem longtext,snapshot_answer longtext,points integer)");
                s.execute("create table personal_exam_question(id bigint primary key,snapshot_stem longtext,snapshot_answer longtext,points integer)");
                s.execute("create table exam_attempt_answer(id bigint primary key,answer_text longtext,awarded_points integer)");
                s.execute("insert into exam_attempt_answer values(1,'legacy',7)");
                s.execute("insert into short_answer_rubric values(1,'old','{}'),(2,'old','{}')");
                // An earlier interrupted run has already added one nullable column.
                s.execute("alter table exam_question add short_rubric_id bigint");
            }
            ShortAnswerMigration.migrate(c);ShortAnswerMigration.migrate(c);
            try(var s=c.createStatement();var r=s.executeQuery("select awarded_points,short_scoring_policy_version from exam_attempt_answer where id=1")) {
                assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(7);assertThat(r.getString(2)).isNull();
            }
            try(var s=c.createStatement();var r=s.executeQuery("select count(*) from hnexam_schema_change where step_key='COMPLETE'")) {r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        }
    }
    @Test void refusesMissingPrerequisites() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:missing_short","sa","")) {
            assertThatThrownBy(()->ShortAnswerMigration.migrate(c)).isInstanceOf(SQLException.class).hasMessageContaining("prerequisite");
        }
    }
}
