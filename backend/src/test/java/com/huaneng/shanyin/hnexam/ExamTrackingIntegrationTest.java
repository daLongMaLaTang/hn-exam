package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:tracking;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop", "app.bootstrap.question-bank-path=", "app.redis.enabled=false", "app.exams.expiry-check-ms=3600000"})
@AutoConfigureMockMvc
class ExamTrackingIntegrationTest {
    @Autowired ExamTrackingService tracking;
    @Autowired ExamService service;
    @Autowired ExamRepository exams;
    @Autowired ExamAttemptRepository attempts;
    @Autowired ExamReminderRepository reminders;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired QuestionRepository questions;
    @Autowired PersonalExamPaperRepository papers;
    @Autowired AuditEventRepository audit;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

    AppUser user(String role) {
        var u = new AppUser(); u.setEmployeeNo("trk_" + UUID.randomUUID().toString().substring(0, 8)); u.setName("追踪验证学员");
        u.setPasswordHash("unused-in-isolated-jwt-tests"); u.setMustChangePassword(false); u.getRoles().add(roles.findByCode(role).orElseThrow()); return users.save(u);
    }
    Exam exam(AppUser... members) {
        var e = new Exam(); e.setTitle("追踪验证"); e.setStatus(ExamStatus.PUBLISHED); e.setAudienceFrozen(true); e.setAudienceMode("USERS");
        Arrays.stream(members).map(AppUser::getId).forEach(e.getAudienceUserIds()::add); return exams.save(e);
    }
    ExamAttempt attempt(Exam exam, AppUser user, AttemptStatus status, Boolean passed) {
        var a = new ExamAttempt(); a.setExam(exam); a.setUser(user); a.setStatus(status); a.setStartedAt(Instant.now());
        a.setDeadline(Instant.now().plusSeconds(1800)); a.setPassed(passed); if (status == AttemptStatus.GRADED) a.setScore(Boolean.TRUE.equals(passed) ? 100 : 20);
        return attempts.save(a);
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(AppUser user, String role) {
        return jwt().jwt(j -> j.subject(user.getEmployeeNo())).authorities(new SimpleGrantedAuthority(role));
    }

    @Test void unstartedPeopleArePresentAndNotAbsentBeforeDeadline() {
        var a = user("CANDIDATE"); var b = user("CANDIDATE"); var e = exam(a, b);
        attempt(e, b, AttemptStatus.SUBMITTED, null);
        var view = tracking.tracking(e.getId());
        assertThat(view.counts()).containsEntry("TOTAL", 2L).containsEntry("NOT_STARTED", 1L).containsEntry("SUBMITTED", 1L).containsEntry("ABSENT", 0L);
        assertThat(view.people()).filteredOn(p -> p.userId().equals(a.getId())).singleElement().satisfies(p -> { assertThat(p.attemptId()).isNull(); assertThat(p.canRemind()).isTrue(); });
    }
    @Test void missingPeopleBecomeAbsentOnlyForFrozenEndedExamsAndCancellationOverrides() {
        var a = user("CANDIDATE"); var e = exam(a); e.setEndTime(Instant.now().minusSeconds(1)); exams.save(e);
        assertThat(tracking.tracking(e.getId()).counts()).containsEntry("ABSENT", 1L);
        e.setStatus(ExamStatus.CANCELLED); exams.save(e);
        assertThat(tracking.tracking(e.getId()).counts()).containsEntry("ABSENT", 0L).containsEntry("CANCELLED", 1L);
        assertThatThrownBy(() -> tracking.remind(e.getId(), List.of(a.getId()), "admin")).hasMessageContaining("无法发送");
    }
    @Test void legacyRosterNeverInventsHistoricalAbsence() {
        var a = user("CANDIDATE"); var e = exam(a); e.setAudienceFrozen(false); e.setStatus(ExamStatus.CLOSED); exams.save(e);
        var view = tracking.tracking(e.getId());
        assertThat(view.frozen()).isFalse(); assertThat(view.counts()).containsEntry("ABSENT", 0L).containsEntry("UNCONFIRMED", 1L);
    }
    @Test void privatePeopleAreExcludedButDisabledFrozenMembersRemain() {
        var a = user("CANDIDATE"); var hidden = user("CANDIDATE"); hidden.setPrivateAdministrator(true); users.save(hidden);
        var e = exam(a, hidden); a.setStatus(UserStatus.DISABLED); users.save(a);
        var view = tracking.tracking(e.getId());
        assertThat(view.people()).singleElement().satisfies(p -> { assertThat(p.userId()).isEqualTo(a.getId()); assertThat(p.active()).isFalse(); assertThat(p.canRemind()).isFalse(); });
        assertThat(tracking.remind(e.getId(), List.of(a.getId(), hidden.getId()), "admin").sent()).isZero();
    }
    @Test void latestAttemptWinsAndAwaitingAutoSubmitIsNotMissing() {
        var a = user("CANDIDATE"); var b = user("CANDIDATE"); var e = exam(a, b);
        attempt(e, a, AttemptStatus.GRADED, true); var latest = attempt(e, a, AttemptStatus.GRADED, false);
        var due = attempt(e, b, AttemptStatus.IN_PROGRESS, null); due.setDeadline(Instant.now().minusSeconds(1)); attempts.save(due);
        var view = tracking.tracking(e.getId());
        assertThat(view.counts()).containsEntry("TOTAL", 2L).containsEntry("FAILED", 1L).containsEntry("PASSED", 0L).containsEntry("PROCESSING", 1L);
        assertThat(view.people()).filteredOn(p -> p.userId().equals(a.getId())).singleElement().satisfies(p -> { assertThat(p.attemptCount()).isEqualTo(2); assertThat(p.attemptId()).isEqualTo(latest.getId()); });
    }
    @Test void wrongQuestionPaperOnlyCountsRecipientsWithPersonalPaper() {
        var a = user("CANDIDATE"); var b = user("CANDIDATE"); var e = exam(a, b); e.setWrongQuestionPaper(true); exams.save(e);
        var paper = new PersonalExamPaper(); paper.setExam(e); paper.setUser(a); papers.save(paper);
        assertThat(tracking.tracking(e.getId()).people()).extracting(p -> p.userId()).containsExactly(a.getId());
    }
    @Test void newAllAudienceIsFrozenAndLaterUsersCannotEnter() {
        var before = user("CANDIDATE"); var hidden = user("CANDIDATE"); hidden.setPrivateAdministrator(true); users.save(hidden);
        var q = new Question(); q.setType(QuestionType.SINGLE_CHOICE); q.setCourseCode("QA"); q.setStem("验证题"); q.setAnswer("A"); q.setOptionsJson("[\"甲\",\"乙\"]"); q.setStatus(QuestionStatus.ACTIVE); questions.save(q);
        var request = new ExamDtos.SaveRequest("全员名单验证", "QA", List.of(), "", 30, 100, 60, null, null, false, 1, false, false, false, 20, List.of(new ExamDtos.QuestionItem(q.getId(), 100, 1)));
        Long id = service.create(request).id(); service.publish(id); var after = user("CANDIDATE");
        assertThat(tracking.tracking(id).frozen()).isTrue();
        assertThat(tracking.tracking(id).people()).extracting(p -> p.userId()).contains(before.getId()).doesNotContain(after.getId(), hidden.getId());
        assertThat(service.candidateList(after.getEmployeeNo())).extracting(ExamDtos.Summary::id).doesNotContain(id);
        assertThatThrownBy(() -> service.start(id, after.getEmployeeNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void reminderSkipsOutsideRosterStartedAndRecentlyRemindedRecipients() {
        var a = user("CANDIDATE"); var b = user("CANDIDATE"); var outsider = user("CANDIDATE"); var e = exam(a, b);
        attempt(e, b, AttemptStatus.IN_PROGRESS, null);
        var sent = tracking.remind(e.getId(), List.of(a.getId(), a.getId(), b.getId(), outsider.getId()), "admin");
        assertThat(sent.sent()).isEqualTo(1); assertThat(sent.skipped()).isEqualTo(2);
        assertThat(tracking.remind(e.getId(), List.of(a.getId()), "admin").sent()).isZero();
        assertThat(tracking.inbox(a.getEmployeeNo(), 0).unreadCount()).isEqualTo(1);
        assertThat(tracking.inbox(b.getEmployeeNo(), 0).total()).isZero();
    }
    @Test void readIsOwnedIdempotentAndResendRefreshesSingleInboxEntry() {
        var a = user("CANDIDATE"); var other = user("CANDIDATE"); var e = exam(a);
        tracking.remind(e.getId(), List.of(a.getId()), "admin"); Long id = tracking.inbox(a.getEmployeeNo(), 0).items().get(0).id();
        assertThatThrownBy(() -> tracking.read(id, other.getEmployeeNo())).hasMessage("通知不存在");
        tracking.read(id, a.getEmployeeNo()); var first = tracking.inbox(a.getEmployeeNo(), 0).items().get(0);
        assertThat(tracking.read(id, a.getEmployeeNo()).readAt()).isEqualTo(first.readAt());
        assertThat(tracking.inbox(a.getEmployeeNo(), 0).unreadCount()).isZero();
        tx.executeWithoutResult(s -> reminders.findById(id).orElseThrow().setSentAt(Instant.now().minusSeconds(86401)));
        assertThat(tracking.remind(e.getId(), List.of(a.getId()), "admin").sent()).isEqualTo(1);
        var inbox = tracking.inbox(a.getEmployeeNo(), 0); assertThat(inbox.total()).isEqualTo(1); assertThat(inbox.unreadCount()).isEqualTo(1);
        e.setStatus(ExamStatus.CANCELLED); exams.save(e); assertThat(tracking.inbox(a.getEmployeeNo(), 0).items().get(0).canOpen()).isFalse();
    }
    @Test void concurrentReminderRequestsCreateOneMessage() throws Exception {
        var a = user("CANDIDATE"); var e = exam(a); var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(1);
        Callable<Integer> send = () -> { ready.await(); return tracking.remind(e.getId(), List.of(a.getId()), "admin").sent(); };
        try { var first = pool.submit(send); var second = pool.submit(send); ready.countDown(); assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1); }
        finally { pool.shutdownNow(); }
        assertThat(tracking.inbox(a.getEmployeeNo(), 0).total()).isEqualTo(1);
    }
    @Test void permissionsValidationAndAuditDoNotExposePrivateActor() throws Exception {
        var a = user("CANDIDATE"); var specialist = user("SPECIALIST"); var admin = user("SYSTEM_ADMIN"); admin.setPrivateAdministrator(true); users.save(admin); var e = exam(a);
        mvc.perform(get("/api/exams/" + e.getId() + "/tracking").with(as(a, "CANDIDATE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/" + e.getId() + "/tracking").with(as(specialist, "SPECIALIST"))).andExpect(status().isOk());
        String path = "/api/exams/" + e.getId() + "/reminders";
        mvc.perform(post(path).with(as(specialist, "SPECIALIST")).contentType("application/json").content("{\"userIds\":[" + a.getId() + "]}")).andExpect(status().isForbidden());
        mvc.perform(post(path).with(as(admin, "SYSTEM_ADMIN")).contentType("application/json").content("{\"userIds\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(as(admin, "SYSTEM_ADMIN")).contentType("application/json").content("{\"userIds\":[" + a.getId() + "]}")).andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(1));
        assertThat(audit.findAll()).filteredOn(event -> path.equals(event.getPath()) && event.getResponseStatus() == 200).allMatch(event -> "维护管理员".equals(event.getActor()));
        assertThat(reminders.findByExamId(e.getId())).singleElement().satisfies(r -> assertThat(r.getSentBy()).isEqualTo("维护管理员"));
    }
    @Test void draftAndClosedExamsCannotBeRemindedAndNotificationIdsAreIsolated() throws Exception {
        var a = user("CANDIDATE"); var b = user("CANDIDATE"); var e = exam(a);
        tracking.remind(e.getId(), List.of(a.getId()), "admin"); Long id = tracking.inbox(a.getEmployeeNo(), 0).items().get(0).id();
        mvc.perform(post("/api/notifications/" + id + "/read").with(as(b, "CANDIDATE"))).andExpect(status().isBadRequest());
        e.setStatus(ExamStatus.CLOSED); exams.save(e); assertThatThrownBy(() -> tracking.remind(e.getId(), List.of(a.getId()), "admin")).hasMessageContaining("无法发送");
        e.setStatus(ExamStatus.DRAFT); exams.save(e); assertThatThrownBy(() -> tracking.tracking(e.getId())).hasMessageContaining("发布考试后");
    }
}
