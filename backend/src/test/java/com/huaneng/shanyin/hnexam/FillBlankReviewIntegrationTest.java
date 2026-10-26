package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:blank_review;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop","app.exams.expiry-check-ms=3600000","app.bootstrap.question-bank-path=","spring.config.import=","app.redis.enabled=false"})
@AutoConfigureMockMvc
class FillBlankReviewIntegrationTest {
    @Autowired FillBlankReviewService blanks;
    @Autowired ExamService exams;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final Deque<String> preparedUsers = new ArrayDeque<>();
    @org.junit.jupiter.api.BeforeEach void prepareRoster() {
        // Publish-time rosters require learners to exist before the exam is published.
        preparedUsers.clear(); for (int i = 0; i < 16; i++) preparedUsers.add(createUser());
    }

    Question question(String reference) {
        var q=new Question();q.setType(QuestionType.FILL_BLANK);q.setStem("测试填空：第一项（ ），第二项（ ）"+UUID.randomUUID());q.setAnswer(reference);q.setCourseCode("TEST");q.setStatus(QuestionStatus.ACTIVE);return questions.save(q);
    }
    Long exam(Question q,int points) {
        var r=new ExamDtos.SaveRequest("集中阅卷验证","TEST",List.of(),"",30,points,points,null,null,false,1,false,false,false,10,List.of(new ExamDtos.QuestionItem(q.getId(),points,1)));
        Long id=exams.create(r).id();exams.publish(id);return id;
    }
    String user() { return preparedUsers.removeFirst(); }
    String createUser() {
        var u=new AppUser();u.setEmployeeNo("blank-"+UUID.randomUUID().toString().substring(0,20));u.setName("测试学员");u.setPasswordHash("unused");u.setMustChangePassword(false);u.setStatus(UserStatus.ACTIVE);u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());return users.save(u).getEmployeeNo();
    }
    Long submit(Long eid,Question q,String text) {
        String user=user();Long id=exams.start(eid,user).attemptId();exams.submit(id,user,new ExamDtos.SubmitRequest(Map.of(q.getId(),text),0));return id;
    }
    void decide(Long eid,FillBlankReviewService.Group g,boolean correct,boolean remember) {
        blanks.decide(eid,new FillBlankReviewService.DecisionRequest(g.questionId(),g.version(),g.slot(),g.submitted(),correct,remember,g.answerIds()),"admin");
    }
    FillBlankReviewService.PartialRequest partial(FillBlankReviewService.Group g, Map<Integer,Boolean> verdicts) {
        return new FillBlankReviewService.PartialRequest(g.questionId(),g.version(),g.slot(),g.submitted(),g.answerIds(),g.slots(),verdicts);
    }
    @Test void partialDecisionPreservesMatchesScoresPermutationsAndDoesNotTeachOrPublish() throws Exception {
        var q=question("甲;乙;丙");q.setStem("包括___、___、___。");questions.save(q);
        Long e=exam(q,5),a=submit(e,q,"[\"甲\",\"乙的别称\",\"错项\"]"),b=submit(e,q,"[\"错项\",\"甲\",\"乙的别称\"]");
        var g=blanks.workspace(e).groups().get(0);
        assertThat(g.count()).isEqualTo(2);
        assertThat(g.slots()).filteredOn(s -> Boolean.TRUE.equals(s.correct())).hasSize(1);
        assertThat(g.scorePreviews()).containsExactly(new FillBlankReviewService.ScorePreview(5,3,0,0));
        var verdicts=new HashMap<Integer,Boolean>();
        g.slots().stream().filter(s -> s.correct()==null).forEach(s -> verdicts.put(s.slot(),s.submitted().equals("乙的别称")));
        mvc.perform(post("/api/exams/"+e+"/blank-review/partial-decisions")
                .with(jwt().jwt(j -> j.subject(user())).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN")))
                .contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(partial(g,verdicts))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.affected").value(2));
        for(Long id:List.of(a,b)) {
            var review=exams.review(id,null);
            assertThat(review.questions().get(0).awardedPoints()).isEqualTo(3);
            assertThat(review.questions().get(0).correct()).isFalse();
            assertThat(review.grade().status()).isEqualTo(AttemptStatus.SUBMITTED);
            assertThat(review.grade().score()).isNull();
        }
        var w=blanks.workspace(e);assertThat(w.rules()).isEmpty();assertThat(w.groups()).isEmpty();assertThat(w.history()).hasSize(2);
        assertThat(w.history()).extracting(FillBlankReviewService.History::correct).containsExactlyInAnyOrder(true,false);
        assertThatThrownBy(() -> blanks.decidePartial(e,partial(g,verdicts),"admin")).hasMessageContaining("状态已变化");
        Long e2=exam(q,5);submit(e2,q,"[\"甲\",\"乙的别称\",\"错项\"]");assertThat(blanks.workspace(e2).pendingSlots()).isEqualTo(2);
        blanks.publish(e,"admin",2);
        var accepted=w.history().stream().filter(FillBlankReviewService.History::correct).findFirst().orElseThrow();
        assertThatThrownBy(() -> blanks.revoke(e,accepted.id(),false,"admin")).hasMessageContaining("已公布");
        blanks.revoke(e,accepted.id(),true,"admin");
        var reopened=blanks.workspace(e);assertThat(reopened.pendingSlots()).isEqualTo(2);
        assertThat(reopened.groups().get(0).slots()).filteredOn(s -> Boolean.TRUE.equals(s.correct())).hasSize(1);
        assertThat(reopened.groups().get(0).slots()).filteredOn(s -> Boolean.FALSE.equals(s.correct())).hasSize(1);
    }
    @Test void partialDecisionRejectsMissingSlotsMatchedSlotChangesAndStalePopulation() {
        var q=question("甲;乙;丙");q.setStem("包括___、___、___。");questions.save(q);
        Long e=exam(q,6);submit(e,q,"[\"甲\",\"新乙\",\"新丙\"]");var g=blanks.workspace(e).groups().get(0);
        var pending=g.slots().stream().filter(s -> s.correct()==null).toList();
        assertThatThrownBy(() -> blanks.decidePartial(e,partial(g,Map.of(pending.get(0).slot(),true)),"admin")).hasMessageContaining("全部待判");
        var all=new HashMap<Integer,Boolean>();g.slots().forEach(s -> all.put(s.slot(),true));
        assertThatThrownBy(() -> blanks.decidePartial(e,partial(g,all),"admin")).hasMessageContaining("已判定");
        assertThat(blanks.workspace(e).history()).isEmpty();
        var verdicts=new HashMap<Integer,Boolean>();pending.forEach(s -> verdicts.put(s.slot(),true));
        submit(e,q,"[\"新丙\",\"甲\",\"新乙\"]");
        assertThatThrownBy(() -> blanks.decidePartial(e,partial(g,verdicts),"admin")).hasMessageContaining("人数或判定状态已变化");
        assertThat(blanks.workspace(e).history()).isEmpty();
    }
    @Test void partialDecisionsCountDuplicateAnswersSeparatelyAndKeepOtherBlanksPending() {
        var q=question("甲;乙;丙;温度");q.setStem("包括___、___、___，温度为___。");questions.save(q);
        Long e=exam(q,8),a=submit(e,q,"[\"同一表达\",\"同一表达\",\"错项\",\"未知温度\"]");
        var g=blanks.workspace(e).groups().get(0);
        assertThat(g.scorePreviews()).containsExactly(new FillBlankReviewService.ScorePreview(8,4,0,1));
        var duplicates=g.slots().stream().filter(s -> s.submitted().equals("同一表达")).toList();assertThat(duplicates).hasSize(2);
        var verdicts=new HashMap<Integer,Boolean>();g.slots().forEach(s -> verdicts.put(s.slot(),s.slot()==duplicates.get(0).slot()));
        blanks.decidePartial(e,partial(g,verdicts),"admin");
        assertThat(exams.review(a,null).questions().get(0).awardedPoints()).isNull();
        var remaining=blanks.workspace(e);assertThat(remaining.pendingSlots()).isEqualTo(1);assertThat(remaining.readyCount()).isZero();
        decide(e,remaining.groups().get(0),false,false);
        assertThat(exams.review(a,null).questions().get(0).awardedPoints()).isEqualTo(2);
    }
    @Test void consecutiveBlanksMatchPermutationsAndMergeUnknownPermutations() {
        var q=question("甲;乙;丙");q.setStem("列出三项（ ）、（ ）、（ ）。");questions.save(q);
        Long e=exam(q,9), correct=submit(e,q,"[\"丙\",\"甲\",\"乙\"]");
        Long a=submit(e,q,"[\"新甲\",\"新乙\",\"丙\"]"), b=submit(e,q,"[\"丙\",\"新乙\",\"新甲\"]");
        var w=blanks.workspace(e);assertThat(w.groups()).hasSize(1);
        assertThat(w.pendingSlots()).isEqualTo(4);
        assertThat(w.groups()).allSatisfy(g->{assertThat(g.count()).isEqualTo(2);assertThat(g.slotLabel()).contains("1–3");});
        assertThat(exams.review(correct,null).questions().get(0).awardedPoints()).isEqualTo(9);
        for(var g:w.groups()) decide(e,g,true,false);
        assertThat(exams.review(a,null).questions().get(0).awardedPoints()).isEqualTo(9);
        assertThat(exams.review(b,null).questions().get(0).awardedPoints()).isEqualTo(9);
        assertThat(exams.review(b,null).questions().get(0).savedAnswer()).isEqualTo("[\"丙\",\"新乙\",\"新甲\"]");
        var decision=blanks.workspace(e).history().get(0);blanks.revoke(e,decision.id(),false,"admin");
        assertThat(blanks.workspace(e).groups()).hasSize(1);assertThat(blanks.workspace(e).groups().get(0).count()).isEqualTo(2);
    }
    @Test void separatePositionsDuplicatesAndMultipleBlocksCannotStealAnswers() {
        var q=question("甲;乙;温度;压力");q.setStem("包括___、___，温度为___，压力为___。");questions.save(q);
        Long e=exam(q,8),a=submit(e,q,"[\"乙\",\"甲\",\"压力\",\"温度\"]");
        assertThat(blanks.workspace(e).groups()).extracting(FillBlankReviewService.Group::slot).containsExactly(3,4);
        for(var g:blanks.workspace(e).groups())decide(e,g,false,false);
        assertThat(exams.review(a,null).questions().get(0).awardedPoints()).isEqualTo(4);
        var repeated=question("甲;甲;乙");repeated.setStem("包括___、___、___。");questions.save(repeated);
        Long e2=exam(repeated,9);submit(e2,repeated,"[\"乙\",\"甲\",\"乙\"]");
        assertThat(blanks.workspace(e2).groups()).hasSize(1);assertThat(blanks.workspace(e2).readyCount()).isZero();
        var alternatives=question("甲~乙;甲");alternatives.setStem("包括___、___。");questions.save(alternatives);
        Long e3=exam(alternatives,10),c=submit(e3,alternatives,"[\"甲\",\"乙\"]");
        assertThat(blanks.workspace(e3).groups()).isEmpty();assertThat(exams.review(c,null).questions().get(0).awardedPoints()).isEqualTo(10);
    }
    @Test void consecutiveAnswerLibraryKeepsTheWholeSetAndRejectRetainsMatchedBlanks() {
        var q=question("甲;乙;丙");q.setStem("包括___、___、___。");questions.save(q);
        Long e=exam(q,9);submit(e,q,"[\"新甲\",\"新乙\",\"丙\"]");
        decide(e,blanks.workspace(e).groups().get(0),true,true);
        Long e2=exam(q,9), accepted=submit(e2,q,"[\"丙\",\"新乙\",\"新甲\"]");
        assertThat(blanks.workspace(e2).groups()).isEmpty();assertThat(exams.review(accepted,null).questions().get(0).awardedPoints()).isEqualTo(9);
        Long rejected=submit(e2,q,"[\"新乙\",\"新乙\",\"丙\"]");
        assertThat(blanks.workspace(e2).groups()).hasSize(1);decide(e2,blanks.workspace(e2).groups().get(0),false,false);
        assertThat(exams.review(rejected,null).questions().get(0).awardedPoints()).isEqualTo(3);
    }
    @Test void legacyPendingPermutationsUpgradeWithoutChangingPublishedScores() {
        var q=question("甲;乙");q.setStem("包括___、___。");questions.save(q);
        Long e=exam(q,10),a=submit(e,q,"[\"乙\",\"甲\"]");
        String legacy=FillBlankReviewService.hash("exam-blank-v1\0"+q.getStem()+"\0"+q.getAnswer());
        jdbc.update("UPDATE fill_blank_mark SET submitted=CASE slot WHEN 1 THEN '乙' ELSE '甲' END, correct=null, source='PENDING', question_version=? WHERE answer_id IN (SELECT id FROM exam_attempt_answer WHERE attempt_id=?)",legacy,a);
        jdbc.update("UPDATE exam_attempt_answer SET awarded_points=null WHERE attempt_id=?",a);
        assertThat(blanks.workspace(e).groups()).isEmpty();assertThat(exams.review(a,null).questions().get(0).awardedPoints()).isEqualTo(10);
        blanks.publish(e,"admin",1);
        jdbc.update("UPDATE fill_blank_mark SET question_version=? WHERE answer_id IN (SELECT id FROM exam_attempt_answer WHERE attempt_id=?)",legacy,a);
        blanks.workspace(e);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fill_blank_mark WHERE question_version=? AND answer_id IN (SELECT id FROM exam_attempt_answer WHERE attempt_id=?)",Integer.class,legacy,a)).isEqualTo(2);
        assertThat(exams.review(a,null).grade().score()).isEqualTo(10);
    }
    @Test void groupsExactTrimmedAnswersPerSlotAndPublishesOnlyWhenReady() {
        var q=question("甲;乙");Long e=exam(q,5);
        Long a=submit(e,q,"[\"新甲\",\"乙\"]"), b=submit(e,q,"[\" 新甲 \",\"\"]");
        var w=blanks.workspace(e);assertThat(w.groups()).hasSize(1);assertThat(w.groups().get(0).count()).isEqualTo(2);assertThat(w.readyCount()).isZero();
        assertThat(exams.results(e)).allSatisfy(g -> {assertThat(g.score()).isNull();assertThat(g.status()).isEqualTo(AttemptStatus.SUBMITTED);});
        decide(e,w.groups().get(0),true,true);
        assertThat(exams.review(a,null).questions().get(0).awardedPoints()).isEqualTo(5);
        assertThat(exams.review(b,null).questions().get(0).awardedPoints()).isEqualTo(3);
        assertThat(exams.review(b,null).questions().get(0).correct()).isFalse();
        assertThat(blanks.workspace(e).readyCount()).isEqualTo(2);
        assertThat(exams.review(a,null).grade().score()).isNull();
        assertThat(blanks.publish(e,"admin",2)).isEqualTo(2);
        assertThat(exams.review(a,null).grade().score()).isEqualTo(5);
        assertThat(blanks.publish(e,"admin",0)).isZero();
    }
    @Test void acceptedExpressionIsVersionAndSlotSpecificAndDisableDoesNotChangeOldScores() {
        var q=question("甲;乙");Long e=exam(q,10);submit(e,q,"[\"新甲\",\"乙\"]");
        decide(e,blanks.workspace(e).groups().get(0),true,true);Long rule=blanks.workspace(e).rules().get(0).id();blanks.publish(e,"admin",1);
        Long e2=exam(q,20), a=submit(e2,q,"[\"新甲\",\"乙\"]");assertThat(blanks.workspace(e2).groups()).isEmpty();blanks.publish(e2,"admin",1);
        assertThat(exams.review(a,null).grade().score()).isEqualTo(20);
        Long e3=exam(q,10);submit(e3,q,"[\"甲\",\"新甲\"]");assertThat(blanks.workspace(e3).groups().get(0).slot()).isEqualTo(2);
        q.setStem(q.getStem()+"修改条件");questions.save(q);Long e4=exam(q,10);submit(e4,q,"[\"新甲\",\"乙\"]");assertThat(blanks.workspace(e4).groups()).hasSize(1);
        blanks.disableRule(rule,"admin");assertThat(exams.review(a,null).grade().score()).isEqualTo(20);
        assertThatThrownBy(() -> blanks.withdrawRuleScores(rule,false,"admin")).hasMessageContaining("已公布");
        assertThat(exams.review(a,null).grade().score()).isEqualTo(20);
        assertThat(blanks.withdrawRuleScores(rule,true,"admin")).isEqualTo(2);
        assertThat(exams.review(a,null).grade().score()).isNull();assertThat(blanks.workspace(e2).groups()).hasSize(1);
    }
    @Test void temporaryApprovalDoesNotTeachAndUndoRequiresExplicitPublishedCorrection() {
        var q=question("甲;乙");Long e=exam(q,10),a=submit(e,q,"[\"别称\",\"乙\"]");
        decide(e,blanks.workspace(e).groups().get(0),true,false);var w=blanks.workspace(e);assertThat(w.rules()).isEmpty();
        Long decision=w.history().get(0).id();blanks.publish(e,"admin",1);
        assertThatThrownBy(() -> blanks.revoke(e,decision,false,"admin")).hasMessageContaining("已公布");
        assertThat(exams.review(a,null).grade().score()).isEqualTo(10);
        blanks.revoke(e,decision,true,"admin");assertThat(blanks.workspace(e).groups()).hasSize(1);assertThat(exams.review(a,null).grade().score()).isNull();
        decide(e,blanks.workspace(e).groups().get(0),false,false);blanks.publish(e,"admin",1);assertThat(exams.review(a,null).grade().score()).isEqualTo(5);
        Long e2=exam(q,10);submit(e2,q,"[\"别称\",\"乙\"]");assertThat(blanks.workspace(e2).groups()).hasSize(1);
    }
    @Test void ongoingUnansweredAttemptsDoNotBlockDecisionsOrUndo() {
        var q=question("甲;乙");Long e=exam(q,10);
        String activeUser=user();Long active=exams.start(e,activeUser).attemptId();
        Long submitted=submit(e,q,"[\"新甲\",\"乙\"]");
        decide(e,blanks.workspace(e).groups().get(0),true,true);
        Long decision=blanks.workspace(e).history().get(0).id();
        assertThat(blanks.publish(e,"admin",1)).isEqualTo(1);
        blanks.revoke(e,decision,true,"admin");
        assertThat(exams.review(submitted,null).grade().score()).isNull();
        assertThat(blanks.workspace(e).groups().get(0).count()).isEqualTo(1);
        assertThat(exams.results(e).stream().filter(a -> a.attemptId().equals(active)).findFirst().orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);
    }
    @Test void changedPopulationAndDuplicateDecisionsCannotSilentlyApply() {
        var q=question("甲;乙");Long e=exam(q,10);submit(e,q,"[\"新\",\"乙\"]");var old=blanks.workspace(e).groups().get(0);
        submit(e,q,"[\"新\",\"乙\"]");assertThatThrownBy(() -> decide(e,old,true,true)).hasMessageContaining("人数已变化");
        var current=blanks.workspace(e).groups().get(0);decide(e,current,true,true);
        assertThatThrownBy(() -> decide(e,current,true,true)).hasMessageContaining("已处理");assertThat(blanks.workspace(e).history()).hasSize(1);
        assertThatThrownBy(() -> blanks.publish(e,"admin",1)).hasMessageContaining("人数已变化");
    }
    @Test void twoConcurrentReviewersCannotScoreTheSameGroupTwice() throws Exception {
        var q=question("甲;乙");Long e=exam(q,10);submit(e,q,"[\"新\",\"乙\"]");var g=blanks.workspace(e).groups().get(0);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<Boolean> action=() -> {gate.await();try{decide(e,g,true,false);return true;}catch(IllegalStateException expected){return false;}};
            var one=pool.submit(action);var two=pool.submit(action);gate.countDown();assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(blanks.workspace(e).history()).hasSize(1);
        } finally {pool.shutdownNow();}
    }
    @Test void resultsStatisticsAndWorkspaceCanLoadTogetherWithoutOppositeLockOrder() throws Exception {
        var q=question("甲;乙");Long e=exam(q,10);
        for(int i=0;i<8;i++) submit(e,q,"[\"新\",\"乙\"]");
        var pool=Executors.newFixedThreadPool(3);
        try {
            for(int round=0;round<6;round++) {
                var gate=new CountDownLatch(1);
                var one=pool.submit(() -> {gate.await();return exams.results(e).size();});
                var two=pool.submit(() -> {gate.await();return exams.statistics(e).participantCount();});
                var three=pool.submit(() -> {gate.await();return blanks.workspace(e).pendingSlots();});
                gate.countDown();assertThat(one.get(15,TimeUnit.SECONDS)).isEqualTo(8);assertThat(two.get(15,TimeUnit.SECONDS)).isEqualTo(8);assertThat(three.get(15,TimeUnit.SECONDS)).isEqualTo(8);
            }
        } finally {pool.shutdownNow();}
    }
    @Test void matchingNeverDiscardsPunctuationCaseUnitsOrNegation() {
        var q=question("A;1.5m;需要");Long e=exam(q,9);submit(e,q,"[\"a\",\"15m\",\"不需要\"]");
        assertThat(blanks.workspace(e).groups()).hasSize(3);
        var q2=question("甲;乙");Long e2=exam(q2,10);submit(e2,q2,"[\"甲。\",\"乙\"]");assertThat(blanks.workspace(e2).groups()).hasSize(1);
    }
    @Test void blankAndExtraInputsRetainExistingZeroPolicyWithoutAnyAiCall() {
        var q=question("甲;乙");Long e=exam(q,10);Long empty=submit(e,q,"[\"\",\"\"]"),extra=submit(e,q,"[\"甲\",\"乙\",\"多填\"]");
        assertThat(blanks.workspace(e).groups()).isEmpty();blanks.publish(e,"admin",2);
        assertThat(exams.review(empty,null).grade().score()).isZero();assertThat(exams.review(extra,null).grade().score()).isZero();
        exams.resolveFillBlank(extra,q.getId(),new FillBlankGrading.Result(2,2,10,true,false),"fake");assertThat(exams.review(extra,null).grade().score()).isZero();
    }
    @Test void cancelledExamsAndForeignDecisionsAreRejected() {
        var q=question("甲");Long e=exam(q,10);submit(e,q,"新");decide(e,blanks.workspace(e).groups().get(0),true,false);Long d=blanks.workspace(e).history().get(0).id();
        Long other=exam(q,10);assertThatThrownBy(() -> blanks.revoke(other,d,true,"admin")).hasMessageContaining("不属于");
        exams.cancel(e,"test","admin");assertThatThrownBy(() -> blanks.publish(e,"admin",1)).hasMessageContaining("取消");
    }
    @Test void onlySystemAdminCanReadOrMutateGradingWorkspace() throws Exception {
        var q=question("甲");Long e=exam(q,10);submit(e,q,"新");
        String actor=user();
        for(String role:List.of("CANDIDATE","TRAINING_ADMIN","DEPARTMENT_LEADER","SPECIALIST")) {
            var auth=jwt().jwt(j -> j.subject(actor)).authorities(new SimpleGrantedAuthority(role));
            mvc.perform(get("/api/exams/"+e+"/blank-review").with(auth)).andExpect(status().isForbidden());
            mvc.perform(post("/api/exams/"+e+"/blank-review/publish").with(auth).contentType("application/json").content("{\"expectedCount\":1}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/exams/"+e+"/blank-review/partial-decisions").with(auth).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/exams/blank-rules/1/disable").with(auth)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/exams/"+e+"/blank-review").with(jwt().jwt(j -> j.subject(actor)).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].count").value(1));
    }
}
