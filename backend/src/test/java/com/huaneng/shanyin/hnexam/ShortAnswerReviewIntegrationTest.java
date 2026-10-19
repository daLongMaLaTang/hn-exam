package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import java.util.concurrent.*;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerScoring.*;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerReviewService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:short_review;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","spring.jpa.hibernate.ddl-auto=create-drop","app.exams.expiry-check-ms=3600000","app.bootstrap.question-bank-path=","spring.config.import=","app.redis.enabled=false"})
@AutoConfigureMockMvc
class ShortAnswerReviewIntegrationTest {
    @Autowired ShortAnswerReviewService review;
    @Autowired ShortAnswerRubricService standards;
    @Autowired FillBlankReviewService publication;
    @Autowired ExamService exams;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ShortAnswerRubricRepository rubricRows;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean OpenAiCompatibleGradingProvider provider;
    final Deque<String> preparedUsers = new ArrayDeque<>();
    @BeforeEach void model() {
        preparedUsers.clear(); for (int i = 0; i < 8; i++) preparedUsers.add(createUser());
        when(provider.structured(anyString(),any(),anyLong())).thenReturn(new OpenAiCompatibleGradingProvider.Structured(json.valueToTree(new Result(List.of(new Finding("p1","MET","甲","满足"),new Finding("p2","MET","乙","满足")),90,List.of())),"mock-model"));
    }
    Question question() {var q=new Question();q.setType(QuestionType.SHORT_ANSWER);q.setStem("验证要求"+UUID.randomUUID());q.setAnswer("甲；乙");q.setCourseCode("TEST");q.setStatus(QuestionStatus.ACTIVE);return questions.save(q);}
    Rubric rubric() {return new Rubric(List.of(new Point("p1","甲要求","甲",1,"CONCEPT","",false,false),new Point("p2","乙要求","乙",1,"CONCEPT","",false,false)),"");}
    Long confirm(Question q) {var v=standards.view(q.getId());v=standards.save(q.getId(),new ShortAnswerRubricService.Save(v.version(),v.id(),rubric()),"admin");return standards.confirm(q.getId(),new ShortAnswerRubricService.Expected(v.version(),v.id()),"admin").id();}
    Long exam(Question q) {Long id=exams.create(new ExamDtos.SaveRequest("简答验证","TEST",List.of(),"",30,10,6,null,null,false,1,false,false,false,10,List.of(new ExamDtos.QuestionItem(q.getId(),10,1)))).id();exams.publish(id);return id;}
    String user() { return preparedUsers.removeFirst(); }
    String createUser() {var u=new AppUser();u.setEmployeeNo("short-"+UUID.randomUUID().toString().substring(0,20));u.setName("测试学员");u.setPasswordHash("unused");u.setMustChangePassword(false);u.setStatus(UserStatus.ACTIVE);u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());return users.save(u).getEmployeeNo();}
    Long submit(Long e,Question q,String text) {String user=user();Long id=exams.start(e,user).attemptId();exams.submit(id,user,new ExamDtos.SubmitRequest(Map.of(q.getId(),text),0));return id;}
    Row row(Long e,Long a) {return review.workspace(e).rows().stream().filter(v->v.attemptId().equals(a)).findFirst().orElseThrow();}
    Target target(Row r) {return new Target(r.attemptId(),r.questionId(),r.revision());}
    @Test void samplesBeforeBatchAndSeparatesConfirmationFromPublication() {
        var q=question();confirm(q);Long e=exam(q);var ids=new ArrayList<Long>();
        for(int i=0;i<4;i++){var a=submit(e,q,"甲乙，附加描述"+i);ids.add(a);review.suggest(a,q.getId());}
        assertThatThrownBy(()->review.accept(e,new Accept(List.of(target(row(e,ids.get(3)))),false),"admin")).hasMessageContaining("抽查");
        for(int i=0;i<3;i++)review.accept(e,new Accept(List.of(target(row(e,ids.get(i)))),true),"admin");
        assertThat(row(e,ids.get(3)).batchEligible()).isTrue();
        review.accept(e,new Accept(List.of(target(row(e,ids.get(3)))),false),"admin");
        assertThat(exams.review(ids.get(3),null).grade().score()).isNull();assertThat(publication.workspace(e).readyCount()).isEqualTo(4);
        publication.publish(e,"admin",4);assertThat(exams.review(ids.get(3),null).grade().score()).isEqualTo(10);
        var r=row(e,ids.get(3));assertThatThrownBy(()->review.undo(e,new Undo(target(r),false),"admin")).hasMessageContaining("已公布");
        review.undo(e,new Undo(target(r),true),"admin");assertThat(exams.review(ids.get(3),null).grade().score()).isNull();
    }
    @Test void sourceVersionAndConfirmedRubricIdentityPreventStaleApproval() {
        var q=question();Long original=confirm(q);Long e=exam(q),a=submit(e,q,"甲乙");review.suggest(a,q.getId());var old=target(row(e,a));
        confirm(q);assertThat(rubricRows.findById(original).orElseThrow().getStatus()).isEqualTo("CONFIRMED");
        assertThatThrownBy(()->review.accept(e,new Accept(List.of(old),true),"admin")).hasMessageContaining("标准已更新");
        review.suggest(a,q.getId());assertThatThrownBy(()->review.accept(e,new Accept(List.of(old),true),"admin")).hasMessageContaining("已变化");
        q.setAnswer("甲；乙；新增条件");questions.save(q);assertThat(standards.view(q.getId()).status()).isEqualTo("EMPTY");
        // The historical paper still grades against its frozen answer and matching confirmed rubric.
        review.accept(e,new Accept(List.of(target(row(e,a))),true),"admin");publication.publish(e,"admin",1);
        Long newer=exam(q),b=submit(newer,q,"甲乙");assertThatThrownBy(()->review.suggest(b,q.getId())).hasMessageContaining("尚无");
    }
    @Test void malformedEvidenceNeverBecomesScoreAndManualFallbackWorksWithoutAi() {
        var q=question();confirm(q);Long e=exam(q),a=submit(e,q,"甲乙");
        when(provider.structured(anyString(),any(),anyLong())).thenReturn(new OpenAiCompatibleGradingProvider.Structured(json.valueToTree(new Result(List.of(new Finding("p1","MET","虚构","满足"),new Finding("p2","MET","乙","满足")),90,List.of())),"mock"));
        assertThatThrownBy(()->review.suggest(a,q.getId())).hasMessageContaining("失败");assertThat(row(e,a).awarded()).isNull();assertThat(row(e,a).state()).isEqualTo("FAILED");
        review.manual(e,new Manual(target(row(e,a)),8,70,"人工核对原文后评分"),"admin");publication.publish(e,"admin",1);assertThat(exams.review(a,null).grade().score()).isEqualTo(8);
    }
    @Test void changedScoresInSampleDisableBatchForThatStandard() {
        var q=question();confirm(q);Long e=exam(q),a=submit(e,q,"甲乙"),b=submit(e,q,"甲乙其他");review.suggest(a,q.getId());review.suggest(b,q.getId());
        review.manual(e,new Manual(target(row(e,a)),5,40,"AI漏识别限定条件"),"admin");
        assertThat(row(e,b).sampleMessage()).contains("抽查发现改分");assertThat(row(e,b).batchEligible()).isFalse();
        review.undo(e,new Undo(target(row(e,a)),false),"admin");review.suggest(a,q.getId());
        assertThat(row(e,b).sampleMessage()).contains("抽查发现改分");
        review.manual(e,new Manual(target(row(e,a)),10,90,"重新核对但保留本标准曾改分的事实"),"admin");
        assertThat(row(e,b).sampleMessage()).contains("抽查发现改分");
    }
    @Test void cancelledExamDiscardsInFlightSuggestion() throws Exception {
        var q=question();confirm(q);Long e=exam(q),a=submit(e,q,"甲乙");var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
        when(provider.structured(anyString(),any(),anyLong())).thenAnswer(invocation->{started.countDown();finish.await(10,TimeUnit.SECONDS);return new OpenAiCompatibleGradingProvider.Structured(json.valueToTree(new Result(List.of(new Finding("p1","MET","甲","满足"),new Finding("p2","MET","乙","满足")),90,List.of())),"mock");});
        var pool=Executors.newSingleThreadExecutor();try{var task=pool.submit(()->review.suggest(a,q.getId()));assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();exams.cancel(e,"取消测试","admin");finish.countDown();assertThatThrownBy(()->task.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);assertThat(exams.review(a,null).questions().get(0).awardedPoints()).isNull();}finally{finish.countDown();pool.shutdownNow();}
    }
    @Test void sourceTrialSeedingIsIdempotentAndNeverConfirmsStandards() {
        var q=question();q.setSource("华能山阴发电有限责任公司安规考试题库 · 简答题第10题");questions.save(q);
        standards.seedTrialDrafts();var draft=standards.view(q.getId());
        assertThat(draft.status()).isEqualTo("DRAFT");assertThat(draft.rubric().points()).hasSize(2);
        standards.seedTrialDrafts();assertThat(standards.view(q.getId()).id()).isEqualTo(draft.id());
        Long e=exam(q),a=submit(e,q,"甲乙");
        assertThatThrownBy(()->review.suggest(a,q.getId())).hasMessageContaining("尚无");
        review.manual(e,new Manual(target(row(e,a)),9,80,"无已确认标准时人工评分"),"admin");
        assertThat(publication.workspace(e).readyCount()).isEqualTo(1);
    }
    @Test void passBoundaryRequiresIndividualReviewEvenAfterSampling() {
        var q=question();confirm(q);Long e=exam(q);var ids=new ArrayList<Long>();
        when(provider.structured(anyString(),any(),anyLong())).thenReturn(new OpenAiCompatibleGradingProvider.Structured(json.valueToTree(new Result(List.of(new Finding("p1","MET","甲","满足"),new Finding("p2","MISSING","","缺失")),50,List.of())),"mock"));
        for(int i=0;i<4;i++){Long a=submit(e,q,"甲补充"+i);ids.add(a);review.suggest(a,q.getId());}
        for(int i=0;i<3;i++)review.accept(e,new Accept(List.of(target(row(e,ids.get(i)))),true),"admin");
        var last=row(e,ids.get(3));assertThat(last.risks()).anyMatch(r->r.contains("及格"));assertThat(last.batchEligible()).isFalse();
        assertThatThrownBy(()->review.accept(e,new Accept(List.of(target(last)),false),"admin")).hasMessageContaining("逐份核对");
    }
    @Test void adminOnlyEndpoints() throws Exception {
        var q=question();Long e=exam(q);String user=user();
        for(String role:List.of("CANDIDATE","TRAINING_ADMIN","SPECIALIST","DEPARTMENT_LEADER")) {
            var auth=jwt().jwt(j->j.subject(user)).authorities(new SimpleGrantedAuthority(role));
            mvc.perform(get("/api/questions/"+q.getId()+"/rubric").with(auth)).andExpect(status().isForbidden());
            mvc.perform(get("/api/exams/"+e+"/short-review").with(auth)).andExpect(status().isForbidden());
            mvc.perform(post("/api/exams/"+e+"/short-review/accept").with(auth).contentType("application/json").content("{\"targets\":[],\"individuallyReviewed\":true}")).andExpect(status().isForbidden());
        }
    }
}
