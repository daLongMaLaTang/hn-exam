package com.huaneng.shanyin.hnexam;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerScoring.*;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerReviewService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:short_review_v2;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","spring.jpa.hibernate.ddl-auto=create-drop","app.exams.expiry-check-ms=3600000","app.bootstrap.question-bank-path=","spring.config.import=","app.redis.enabled=false","app.ai.short-automation-enabled=false"})
@AutoConfigureMockMvc
class ShortAnswerReviewIntegrationTest {
    @DynamicPropertySource static void isolatedMysql(DynamicPropertyRegistry registry) {
        if(!Boolean.getBoolean("hnexam.short.mysql")) return;
        registry.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:3306/hn_exam_short_locktest_20261021?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username",()->System.getenv("HNEXAM_SHORT_TEST_USER"));
        registry.add("spring.datasource.password",()->System.getenv("HNEXAM_SHORT_TEST_PASSWORD"));
    }
    @Autowired ShortAnswerReviewService review;
    @Autowired ShortAnswerAutomationService jobs;
    @Autowired ShortAnswerRubricService standards;
    @Autowired FillBlankReviewService publication;
    @Autowired ExamService exams;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ExamAttemptRepository attempts;
    @Autowired ExamRepository examRows;
    @Autowired WrongQuestionService wrong;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @PersistenceContext EntityManager em;
    @MockitoBean OpenAiCompatibleGradingProvider provider;
    List<AppUser> candidates;
    Rubric rubric() {return new Rubric(List.of(new Point("p1","甲","甲",3,"CONCEPT","表达甲",false,false),new Point("p2","乙","乙",7,"ORDER","表达乙",false,true)),"",FORMAT,POLICY,List.of());}
    Decision decision(String verdict) {return new Decision(FORMAT,POLICY,List.of(new Finding("p1","FULL","甲","满足"),new Finding("p2",verdict,"","遗漏或无法判断")),List.of());}
    OpenAiCompatibleGradingProvider.Structured response(Object v) {return new OpenAiCompatibleGradingProvider.Structured(json.valueToTree(v),"mock-model");}
    @BeforeEach void model() {
        candidates=new ArrayList<>();for(int i=0;i<5;i++) candidates.add(user());
        when(provider.shortConfigurationFingerprint()).thenReturn("mock-config-v1");
        when(provider.structured(anyString(),any(),anyLong())).thenAnswer(inv->response(inv.getArgument(1)!=null && ((Map<?,?>)inv.getArgument(1)).containsKey("rubric")?decision("NONE"):rubric()));
    }
    @AfterEach void stopRemaining() {
        tx.executeWithoutResult(s->{em.createQuery("update ShortAnswerJobItem i set i.state='SKIPPED' where i.state not in ('DONE','SKIPPED','FAILED','REVIEW')").executeUpdate();em.createQuery("update ShortAnswerJob j set j.activeKey=null").executeUpdate();});
    }
    AppUser user() {var u=new AppUser();u.setEmployeeNo("short-"+UUID.randomUUID().toString().substring(0,20));u.setName("测试学员");u.setPasswordHash("unused");u.setMustChangePassword(false);u.setStatus(UserStatus.ACTIVE);u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());return users.save(u);}
    Question question() {var q=new Question();q.setType(QuestionType.SHORT_ANSWER);q.setStem("验证要求"+UUID.randomUUID());q.setAnswer("甲；乙");q.setCourseCode("TEST");q.setStatus(QuestionStatus.ACTIVE);return questions.save(q);}
    Long exam(Question q) {return exam(q,10);}
    Long exam(Question q,int maximum) {Long id=exams.create(new ExamDtos.SaveRequest("简答验证","TEST",List.of(),"",30,maximum,Math.max(1,maximum/2),null,null,false,1,false,false,false,10,List.of(new ExamDtos.QuestionItem(q.getId(),maximum,1)))).id();exams.publish(id,"admin");return id;}
    Long submit(Long e,Question q,String text,int index) {String u=candidates.get(index).getEmployeeNo();Long id=exams.start(e,u).attemptId();exams.submit(id,u,new ExamDtos.SubmitRequest(Map.of(q.getId(),text),0));return id;}
    Row row(Long e,Long a) {return review.workspace(e).rows().stream().filter(v->v.attemptId().equals(a)).findFirst().orElseThrow();}
    Target target(Row r) {return new Target(r.attemptId(),r.questionId(),r.revision());}
    void due() {tx.executeWithoutResult(s->em.createQuery("update ShortAnswerJobItem i set i.nextRunAt=:now where i.state='WAITING'").setParameter("now",Instant.now().minusSeconds(1)).executeUpdate());}
    void drain() {
        // These tests execute immediately after enqueueing; database timestamp rounding must not delay QUEUED work.
        tx.executeWithoutResult(s->em.createQuery("update ShortAnswerJobItem i set i.nextRunAt=:now where i.state='QUEUED'").setParameter("now",Instant.now().minusSeconds(1)).executeUpdate());
        for(int n=0;n<1000;n++) {var id=jobs.claim();if(id==null)return;jobs.process(id);}throw new AssertionError("queue did not settle");
    }
    AttemptAnswer answer(Long id) {return tx.execute(s->{var v=attempts.findById(id).orElseThrow().getAnswers().get(0);return v;});}
    void assertSuggestion(Long e,Long a,int points) {
        assertThat(row(e,a).suggested()).isEqualTo(points);assertThat(row(e,a).awarded()).isNull();
        assertThat(answer(a).getGradingMethod()).isNull();assertThat(answer(a).getCorrect()).isNull();
    }
    void confirm(Long e,Long a) {review.applySuggestions(e,new ApplySuggestions(List.of(target(row(e,a)))),"admin");}
    @Test void aiSuggestionsRequireHumanConfirmationBeforePublication() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);
        verifyNoInteractions(provider);
        var job=jobs.start(e,"admin");assertThat(jobs.start(e,"admin").id()).isEqualTo(job.id());
        q.setAnswer("后来修改题库");questions.save(q);drain();
        assertSuggestion(e,a,3);assertThat(row(e,a).reference()).isEqualTo("甲；乙");
        assertThat(row(e,a).state()).isEqualTo("SUGGESTED");assertThat(row(e,a).safeToApply()).isTrue();
        assertThat(exams.review(a,null).grade().score()).isNull();assertThat(jobs.progress(e)).allMatch(p->p.pending()==0);
        assertThat(wrong.unresolved(candidates.get(0).getId(),"EXAM")).isEmpty();
        assertThat(publication.workspace(e).readyCount()).isZero();
        assertThatThrownBy(()->publication.publish(e,"admin",1)).isInstanceOf(IllegalStateException.class);
        jobs.start(e,"admin");drain();verify(provider,times(2)).structured(anyString(),any(),anyLong());
        confirm(e,a);assertThat(answer(a).getGradingMethod()).isEqualTo(GradingMethod.MANUAL);
        publication.publish(e,"admin",1);
        assertThat(exams.review(a,null).grade().score()).isEqualTo(3);
        assertThat(wrong.unresolved(candidates.get(0).getId(),"EXAM")).extracting(Question::getId).contains(q.getId());
    }
    @Test void busyAdmissionWaitsDuringPreparationAndGradingWithoutConsumingRetries() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);jobs.start(e,"admin");
        when(provider.structured(anyString(),any(),anyLong())).thenThrow(new AiCallLimitException("busy",true));
        for(int n=0;n<4;n++) {due();drain();}
        assertThat(jobs.progress(e)).allMatch(p->p.failed()==0 && p.review()==0 && p.pending()>0);
        assertThat(row(e,a).awarded()).isNull();
        doAnswer(inv->{
            if(((Map<?,?>)inv.getArgument(1)).containsKey("rubric")) throw new AiCallLimitException("busy",true);
            return response(rubric());
        }).when(provider).structured(anyString(),any(),anyLong());
        for(int n=0;n<4;n++) {due();drain();}
        assertThat(jobs.progress(e)).allMatch(p->p.failed()==0 && p.review()==0);
        assertThat(row(e,a).awarded()).isNull();
        doReturn(response(decision("NONE"))).when(provider).structured(anyString(),any(),anyLong());
        due();drain();assertSuggestion(e,a,3);
        assertThat(jobs.progress(e)).allMatch(p->p.pending()==0);
    }
    @Test void parentLocksDoNotLockSharedQuestionRows() throws Exception {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);drain();jobs.start(e,"admin");
        var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var holder=pool.submit(()->tx.executeWithoutResult(s->{
                em.createNativeQuery("select id from qb_question where id=:id for update").setParameter("id",q.getId()).getSingleResult();
                locked.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("lock holder timed out");}
                catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new RuntimeException(ex);}
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            pool.submit(()->{var work=jobs.claim();assertThat(work).isNotNull();jobs.process(work);review.workspace(e);}).get(5,TimeUnit.SECONDS);
            release.countDown();holder.get(5,TimeUnit.SECONDS);
            assertSuggestion(e,a,3);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void rulesAndExactAnswersReuseAcrossExamsWithDifferentMaximum() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);jobs.start(e,"admin");drain();
        q.setStem(" "+q.getStem()+"\r\n");q.setAnswer(" 甲；乙 ");questions.save(q);
        Long e2=exam(q,20),b=submit(e2,q,"甲",1);jobs.start(e2,"admin");drain();
        assertThat(row(e,a).rubricId()).isEqualTo(row(e2,b).rubricId());assertSuggestion(e2,b,6);
        verify(provider,times(2)).structured(anyString(),any(),anyLong());
        assertThat(answer(a).getShortDecisionCacheId()).isEqualTo(answer(b).getShortDecisionCacheId());
    }
    @Test void modelChangeMissesDecisionCacheButKeepsRule() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);jobs.start(e,"admin");drain();
        when(provider.shortConfigurationFingerprint()).thenReturn("mock-config-v2");
        Long b=submit(e,q,"甲",1);jobs.start(e,"admin");drain();
        assertSuggestion(e,b,3);assertThat(row(e,a).rubricId()).isEqualTo(row(e,b).rubricId());
        verify(provider,times(3)).structured(anyString(),any(),anyLong());
    }
    @Test void uncertainAndTechnicalFailuresNeverBecomeFinalZero() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);drain();
        when(provider.structured(anyString(),any(),anyLong())).thenReturn(response(decision("REVIEW")));
        jobs.start(e,"admin");drain();assertThat(row(e,a).awarded()).isNull();assertThat(row(e,a).state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(publication.workspace(e).readyCount()).isZero();
        Long b=submit(e,q,"甲其他",1);when(provider.structured(anyString(),any(),anyLong())).thenThrow(new IllegalStateException("mock offline"));
        jobs.start(e,"admin");drain();due();drain();due();drain();
        assertThat(row(e,b).awarded()).isNull();assertThat(row(e,b).state()).isEqualTo("FAILED");assertThat(jobs.progress(e).get(0).failed()).isGreaterThan(0);
    }
    @Test void manualDuringCallWinsAndUndoInvalidatesQueuedRevision() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);drain();
        when(provider.structured(anyString(),any(),anyLong())).thenAnswer(inv->{review.manual(e,new Manual(target(row(e,a)),8,null,"人工核对"),"admin");return response(decision("NONE"));});
        jobs.start(e,"admin");drain();assertThat(row(e,a).awarded()).isEqualTo(8);assertThat(answer(a).getGradingMethod()).isEqualTo(GradingMethod.MANUAL);
        review.undo(e,new Undo(target(row(e,a)),false),"admin");jobs.start(e,"admin");
        review.manual(e,new Manual(target(row(e,a)),9,null,"人工"),"admin");review.undo(e,new Undo(target(row(e,a)),false),"admin");drain();
        assertThat(row(e,a).awarded()).isNull();
    }
    @Test void manualOverrideInvalidatesCacheWithoutChangingOtherFinals() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0),b=submit(e,q,"甲",1);jobs.start(e,"admin");drain();
        confirm(e,a);confirm(e,b);
        review.undo(e,new Undo(target(row(e,a)),false),"admin");review.manual(e,new Manual(target(row(e,a)),8,null,"原判定有误"),"admin");
        Long c=submit(e,q,"甲",2);jobs.start(e,"admin");drain();
        assertThat(row(e,b).awarded()).isEqualTo(3);assertThat(row(e,c).awarded()).isNull();assertThat(row(e,c).risks()).anyMatch(t->t.contains("人工"));
        assertThat(answer(a).getAiSuggestedPoints()).isEqualTo(3);assertThat(answer(a).getCorrect()).isTrue();
    }
    @Test void cancellationDiscardsInflightResult() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);drain();
        when(provider.structured(anyString(),any(),anyLong())).thenAnswer(inv->{exams.cancel(e,"取消测试","admin");return response(decision("NONE"));});
        jobs.start(e,"admin");drain();assertThat(answer(a).getAwardedPoints()).isNull();
    }
    @Test void expiredLeaseResumesIdempotently() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);jobs.start(e,"admin");var id=jobs.claim();assertThat(id).isNotNull();
        tx.executeWithoutResult(s->em.find(ShortAnswerJobItem.class,id.id()).setLeaseUntil(Instant.now().minusSeconds(1)));
        var resumed=jobs.claim();assertThat(resumed.id()).isEqualTo(id.id());assertThat(resumed.token()).isNotEqualTo(id.token());
        jobs.process(id);verify(provider,never()).structured(anyString(),any(),anyLong());
        jobs.process(resumed);drain();jobs.process(id);assertSuggestion(e,a,3);
        Long count=tx.execute(s->em.createQuery("select count(r) from GradingRevision r where r.attemptId=:id",Long.class).setParameter("id",a).getSingleResult());
        assertThat(count).isZero();
    }
    @Test void emptyAnswersAndPublishedScoresDoNotNeedAi() {
        var q=question();Long e=exam(q),a=submit(e,q,"\u3000 \t",0),b=submit(e,q,"甲",1);
        tx.executeWithoutResult(s->{var attempt=attempts.findById(b).orElseThrow();attempt.setStatus(AttemptStatus.GRADED);attempt.setScore(7);attempt.getAnswers().get(0).setAwardedPoints(7);});
        jobs.start(e,"admin");assertThat(row(e,a).awarded()).isZero();assertThat(row(e,b).awarded()).isEqualTo(7);verifyNoInteractions(provider);
        assertThat(publication.workspace(e).readyCount()).isEqualTo(1);
        publication.publish(e,"admin",1);assertThat(exams.review(a,null).grade().score()).isZero();
    }
    @Test void nonemptyZeroAndFullSuggestionsBothNeedConfirmationAndCanBeAdjusted() {
        var q=question();Long e=exam(q),a=submit(e,q,"完全无关",0),b=submit(e,q,"甲乙",1);drain();
        when(provider.structured(anyString(),any(),anyLong())).thenAnswer(inv->{
            var input=(Map<?,?>)inv.getArgument(1);boolean full="甲乙".equals(input.get("candidateAnswer"));
            return response(new Decision(FORMAT,POLICY,List.of(
                    new Finding("p1",full?"FULL":"NONE",full?"甲":"","判定依据"),
                    new Finding("p2",full?"FULL":"NONE",full?"乙":"","判定依据")),List.of()));
        });
        jobs.start(e,"admin");drain();assertSuggestion(e,a,0);assertSuggestion(e,b,10);
        assertThat(publication.workspace(e).readyCount()).isZero();
        confirm(e,a);assertThat(row(e,a).awarded()).isZero();
        review.manual(e,new Manual(target(row(e,b)),9,null,"人工调整"),"admin");
        assertThat(row(e,b).suggested()).isEqualTo(10);assertThat(row(e,b).awarded()).isEqualTo(9);
        assertThat(publication.workspace(e).readyCount()).isEqualTo(2);
    }
    @Test void missingHistoricalSnapshotNeverBorrowsCurrentQuestionForAi() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);
        tx.executeWithoutResult(s->{var snap=examRows.findById(e).orElseThrow().getQuestions().get(0);snap.setSnapshotType(null);snap.setSnapshotStem(null);snap.setSnapshotAnswer(null);});
        exams.review(a,null);jobs.start(e,"admin");drain();assertThat(row(e,a).awarded()).isNull();verify(provider,never()).structured(anyString(),any(),anyLong());
    }
    @Test void invalidRulesCanBeManuallyRepairedWithoutBlockingPublication() {
        when(provider.structured(anyString(),any(),anyLong())).thenReturn(response(new Rubric(rubric().points(),"",FORMAT,POLICY,List.of("歧义"))));
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);jobs.start(e,"admin");drain();assertThat(row(e,a).awarded()).isNull();
        var v=standards.view(q.getId());var saved=standards.save(q.getId(),new ShortAnswerRubricService.Save(v.version(),v.id(),rubric()),"admin");
        standards.confirm(q.getId(),new ShortAnswerRubricService.Expected(saved.version(),saved.id()),"admin");
        when(provider.structured(anyString(),any(),anyLong())).thenReturn(response(decision("NONE")));
        jobs.start(e,"admin");drain();assertSuggestion(e,a,3);
    }
    @Test void legacyNullPolicyKeepsItsOldWrongRule() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0),b=submit(e,q,"甲",1);
        review.manual(e,new Manual(target(row(e,a)),4,0d,"新记录"),"admin");review.manual(e,new Manual(target(row(e,b)),9,0d,"历史记录"),"admin");
        tx.executeWithoutResult(s->attempts.findById(b).orElseThrow().getAnswers().get(0).setShortScoringPolicyVersion(null));publication.publish(e,"admin",2);
        assertThat(wrong.unresolved(candidates.get(0).getId(),"EXAM")).isEmpty();
        assertThat(wrong.unresolved(candidates.get(1).getId(),"EXAM")).extracting(Question::getId).contains(q.getId());
    }
    @Test void oldRouteEnqueuesAndPermissionsArePreserved() throws Exception {
        var admin=users.findByEmployeeNo("admin").orElseThrow();admin.setMustChangePassword(false);users.save(admin);
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);
        mvc.perform(post("/api/exams/"+e+"/short-review/jobs").with(jwt().jwt(j->j.subject(candidates.get(0).getEmployeeNo())).authorities(new SimpleGrantedAuthority("CANDIDATE")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/attempts/"+a+"/short-ai/"+q.getId()).with(jwt().jwt(j->j.subject("admin")).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN")))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        verifyNoInteractions(provider);
    }
    @Test void oneHundredFortyAnswersNeedOneRuleAndOneDecision() {
        for(int i=candidates.size();i<140;i++) candidates.add(user());
        var q=question();Long e=exam(q);for(int i=0;i<140;i++) submit(e,q,"甲",i);
        jobs.start(e,"admin");drain();assertThat(review.workspace(e).suggested()).isEqualTo(140);
        assertThat(review.workspace(e).accepted()).isZero();verify(provider,times(2)).structured(anyString(),any(),anyLong());
    }
    @Test void concurrentPreparationCoalescesAcrossExamsAndDoesNotHoldTransactionDuringAi() throws Exception {
        var q=question();Long e=exam(q),e2=exam(q),a=submit(e,q,"甲",0),b=submit(e2,q,"甲",1);
        jobs.start(e,"admin");jobs.start(e2,"admin");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(!((Map<?,?>)inv.getArgument(1)).containsKey("rubric")) {entered.countDown();assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return response(rubric());}
            return response(decision("NONE"));
        }).when(provider).structured(anyString(),any(),anyLong());
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first=jobs.claim();var future=executor.submit(()->jobs.process(first));assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=jobs.claim();assertThat(second).isNotEqualTo(first);jobs.process(second);
            release.countDown();future.get(10,java.util.concurrent.TimeUnit.SECONDS);due();drain();due();drain();
            assertSuggestion(e,a,3);assertSuggestion(e2,b,3);
            verify(provider,times(2)).structured(anyString(),any(),anyLong());
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void personalWrongQuestionPaperLocksItsOwnMaximumAndRule() {
        var q=question();Long e=exam(q),a=submit(e,q,"甲",0);
        review.manual(e,new Manual(target(row(e,a)),3,null,"错题来源"),"admin");publication.publish(e,"admin",1);
        Long personal=exams.create(new ExamDtos.SaveRequest("个人错题卷","TEST",List.of(),"",30,20,10,null,null,false,1,false,true,true,10,List.of())).id();
        exams.publish(personal,"admin");Long b=submit(personal,q,"甲",0);jobs.start(personal,"admin");drain();
        assertThat(row(personal,b).maximum()).isEqualTo(20);assertSuggestion(personal,b,6);
        assertThat(row(personal,b).rubricId()).isNotNull();
    }
}
