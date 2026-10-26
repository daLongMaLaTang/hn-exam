package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.tools.AnswerSyncMigration;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class AnswerSyncMigrationTest {
    @Test void additiveAndRepeatableWithoutChangingHistoricalScores() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:answer_migration"); var s = db.createStatement()) {
            s.execute("create table exam_attempt(id bigint primary key, score int, status varchar(20))");
            s.execute("insert into exam_attempt values(1,85,'GRADED')");
            AnswerSyncMigration.migrate(db);
            s.execute("update exam_attempt set answer_revision=7,last_answer_request_id='existing'");
            AnswerSyncMigration.migrate(db);
            try (var r = s.executeQuery("select * from exam_attempt")) {
                assertThat(r.next()).isTrue(); assertThat(r.getInt("score")).isEqualTo(85);
                assertThat(r.getString("status")).isEqualTo("GRADED");
                assertThat(r.getLong("answer_revision")).isEqualTo(7);
                assertThat(r.getString("last_answer_request_id")).isEqualTo("existing");
            }
            try (var r = s.executeQuery("select count(*) from hnexam_schema_change")) { r.next(); assertThat(r.getInt(1)).isEqualTo(1); }
        }
    }
}
