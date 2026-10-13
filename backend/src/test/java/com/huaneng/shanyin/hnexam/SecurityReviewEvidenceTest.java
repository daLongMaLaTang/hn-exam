package com.huaneng.shanyin.hnexam;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.*;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Regression tests plus the remaining shared-initial-password evidence; no remote AI is used. */
@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:security_evidence;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "app.redis.enabled=false", "app.bootstrap.question-bank-path=", "app.ai.api-key=", "app.jwt.secret=", "app.jwt.require-configured=false"})
@AutoConfigureMockMvc
class SecurityReviewEvidenceTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UserRepository users;
    @Autowired RoleRepository roles; @Autowired QuestionRepository questions; @Autowired AuthService auth;
    @Autowired AuthStateService states; @Autowired ExamService exams; @Autowired DataSource dataSource;
    @Autowired PracticeRecordRepository practices;
    @MockitoBean OpenAiCompatibleGradingProvider provider;
    AppUser user(String role) {
        var u=new AppUser();u.setEmployeeNo("audit-"+UUID.randomUUID().toString().substring(0,16));u.setName("隔离安全验证");
        u.setPasswordHash("not-used-for-login");u.setMustChangePassword(false);u.getRoles().add(roles.findByCode(role).orElseThrow());return users.save(u);
    }
    String bearer(AppUser u){return "Bearer "+auth.tokenFor(u).accessToken();}
    Question question(QuestionType type) {var q=new Question();q.setType(type);q.setStatus(QuestionStatus.ACTIVE);q.setCourseCode("AUDIT_ONLY");q.setStem("隔离题干");q.setAnswer(type==QuestionType.FILL_BLANK?"验电":"A");q.setOptionsJson("[\"A\",\"B\"]");return questions.save(q);}
    @Test void evidenceSharedInitialPasswordCanClaimTwoNewAccounts() throws Exception {
        var admin=user("SYSTEM_ADMIN");
        for(int i=0;i<2;i++) {
            String number="new-"+UUID.randomUUID().toString().substring(0,16);
            mvc.perform(post("/api/users").header("Authorization",bearer(admin)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("employeeNo",number,"name","隔离新学员")))).andExpect(status().isOk());
            var login=auth.login(new LoginRequest(number,"Candidate@12345"),"127.0.0.1");
            assertThat(login.mustChangePassword()).isTrue();
            mvc.perform(post("/api/auth/password").header("Authorization","Bearer "+login.accessToken()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("oldPassword","Candidate@12345","newPassword","ClaimedOnly-20261011"))))
                .andExpect(status().isOk()).andExpect(jsonPath("mustChangePassword").value(false));
        }
    }
    @Test void knownDefaultSigningKeyCannotForgeIdentity() throws Exception {
        var target=user("SYSTEM_ADMIN");
        var key=new SecretKeySpec("hnExam-local-development-secret-change-before-release-2026".getBytes(StandardCharsets.UTF_8),"HmacSHA256");
        var signer=new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var claims=JwtClaimsSet.builder().issuer("hn-exam").subject(target.getEmployeeNo()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("version",0).build();
        String forged=signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        mvc.perform(get("/api/users").header("Authorization","Bearer "+forged)).andExpect(status().isUnauthorized());
    }
    @Test void practiceAiCallDoesNotHoldTransactionOrDatabaseConnection() throws Exception {
        var u=user("CANDIDATE");var q=question(QuestionType.FILL_BLANK);
        when(provider.suggest(any())).thenAnswer(invocation->{
            SubjectiveGradingProvider.Request request=invocation.getArgument(0);
            assertThat(request.userId()).isEqualTo(u.getId());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            return Optional.of(new SubjectiveGradingProvider.Suggestion(1,100,"模拟语义等价","TEST","no-network"));
        });
        mvc.perform(post("/api/practice/questions/"+q.getId()+"/submit").header("Authorization",bearer(u)).contentType("application/json").content("{\"answer\":\"确认无电压\"}"))
            .andExpect(status().isOk());verify(provider).suggest(any());
    }
    @Test void loginThrottleBlocksPasswordSprayAndRemovesExpiredNames() {
        var redis=mock(RedisSupport.class);when(redis.key(anyString())).thenAnswer(i->i.getArgument(0));when(redis.get(anyString())).thenReturn(Optional.empty());
        var throttle=new LoginThrottleService(redis);ReflectionTestUtils.setField(throttle,"maxFailures",8);ReflectionTestUtils.setField(throttle,"windowSeconds",900L);
        for(int i=0;i<8;i++)throttle.failed("same-name","127.0.0.1");
        assertThatThrownBy(()->throttle.checkAllowed("same-name","127.0.0.1")).isInstanceOf(TooManyLoginAttemptsException.class);
        assertThatCode(()->throttle.checkAllowed("different-name","127.0.0.1")).doesNotThrowAnyException();
        for(int i=0;i<200;i++)throttle.failed("spray-"+i,"127.0.0.2");
        assertThatThrownBy(()->throttle.checkAllowed("unused-name","127.0.0.2")).isInstanceOf(TooManyLoginAttemptsException.class);
        throttle.succeeded("spray-0","127.0.0.2");
        assertThatThrownBy(()->throttle.checkAllowed("unused-name","127.0.0.2")).isInstanceOf(TooManyLoginAttemptsException.class);
        for(int i=0;i<20;i++)throttle.failed("distributed-target","ip-"+i);
        assertThatThrownBy(()->throttle.checkAllowed("distributed-target","new-ip")).isInstanceOf(TooManyLoginAttemptsException.class);
        int retained=((Map<?,?>)ReflectionTestUtils.getField(throttle,"local")).size();
        ReflectionTestUtils.setField(throttle,"windowSeconds",-1L);
        for(int i=0;i<1000;i++)throttle.failed("expired-"+i,"127.0.0.3");
        throttle.cleanupExpired();
        assertThat((Map<?,?>)ReflectionTestUtils.getField(throttle,"local")).hasSize(retained);
    }
    @Test void practiceRechecksExamProtectionAfterWaitingForAi() throws Exception {
        var u=user("CANDIDATE");var q=question(QuestionType.FILL_BLANK);
        var exam=exams.create(new ExamDtos.SaveRequest("AI等待期间开考","AUDIT_ONLY",List.of(),"",30,100,60,null,null,false,1,false,false,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),100,1))));
        exams.publish(exam.id());
        when(provider.suggest(any())).thenAnswer(invocation->{
            exams.start(exam.id(),u.getEmployeeNo());
            return Optional.of(new SubjectiveGradingProvider.Suggestion(1,100,"模拟语义等价","TEST","no-network"));
        });
        mvc.perform(post("/api/practice/questions/"+q.getId()+"/submit").header("Authorization",bearer(u)).contentType("application/json").content("{\"answer\":\"确认无电压\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("correctAnswer").doesNotExist());
    }
    @Test void aiLimitLeavesPracticeUnassessedInsteadOfWrong() throws Exception {
        var u=user("CANDIDATE"); var q=question(QuestionType.FILL_BLANK);
        when(provider.suggest(any())).thenThrow(new AiCallLimitException("今日AI调用额度已用完"));
        mvc.perform(post("/api/practice/questions/"+q.getId()+"/submit").header("Authorization",bearer(u))
                .contentType("application/json").content("{\"answer\":\"确认无电压\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("correct").doesNotExist());
        assertThat(practices.findByUserIdOrderByAnsweredAtDesc(u.getId())).singleElement()
            .satisfies(record->assertThat(record.getAssessed()).isFalse());
    }
    @Test void loginFailureCacheHasAHardSizeLimit() {
        var redis=mock(RedisSupport.class);when(redis.key(anyString())).thenAnswer(i->i.getArgument(0));
        var throttle=new LoginThrottleService(redis);
        ReflectionTestUtils.setField(throttle,"windowSeconds",900L);
        ReflectionTestUtils.setField(throttle,"maxLocalEntries",3);
        throttle.failed("one","one-ip");
        assertThatThrownBy(()->throttle.failed("two","two-ip")).isInstanceOf(TooManyLoginAttemptsException.class);
        assertThat((Map<?,?>)ReflectionTestUtils.getField(throttle,"local")).hasSize(3);
    }
    @Test void practiceRejectsAReferenceChangedWhileAiWasRunning() throws Exception {
        var u=user("CANDIDATE");var q=question(QuestionType.FILL_BLANK);
        when(provider.suggest(any())).thenAnswer(invocation->{
            q.setAnswer("停电");questions.save(q);
            return Optional.of(new SubjectiveGradingProvider.Suggestion(1,100,"模拟语义等价","TEST","no-network"));
        });
        mvc.perform(post("/api/practice/questions/"+q.getId()+"/submit").header("Authorization",bearer(u)).contentType("application/json").content("{\"answer\":\"确认无电压\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("correctAnswer").doesNotExist());
    }
    @Test void candidateCannotReadAnotherAttemptOrManageAiUsersAndGrades() throws Exception {
        var owner=user("CANDIDATE");var other=user("CANDIDATE");var q=question(QuestionType.SINGLE_CHOICE);
        var exam=exams.create(new ExamDtos.SaveRequest("隔离安全考试","AUDIT_ONLY",List.of(),"",30,100,60,null,null,false,1,false,false,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),100,1))));
        exams.publish(exam.id());var attempt=exams.start(exam.id(),owner.getEmployeeNo());
        mvc.perform(get("/api/candidate/attempts/"+attempt.attemptId()).header("Authorization",bearer(other))).andExpect(status().isBadRequest());
        for(String path:List.of("/api/users","/api/questions","/api/ai/settings","/api/dashboard/monthly-learning","/api/profiles/"+owner.getId()))
            mvc.perform(get(path).header("Authorization",bearer(other))).andExpect(status().isForbidden());
        mvc.perform(get("/api/candidate/attempts/"+attempt.attemptId()).header("Authorization",bearer(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("questions[0].correctAnswer").doesNotExist());
        mvc.perform(get("/api/candidate/attempts/"+attempt.attemptId()+"/review").header("Authorization",bearer(owner))).andExpect(status().isConflict());
    }
}
