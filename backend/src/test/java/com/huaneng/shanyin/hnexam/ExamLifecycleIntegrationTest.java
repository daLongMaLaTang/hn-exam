package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.controller.PracticeController;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.*;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.ExamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:exam_lifecycle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop", "app.exams.expiry-check-ms=3600000", "app.bootstrap.question-bank-path=", "spring.config.import=", "app.redis.enabled=false"})
@AutoConfigureMockMvc
class ExamLifecycleIntegrationTest {
    @Autowired ExamService service;
    @Autowired com.huaneng.shanyin.hnexam.service.FillBlankReviewService blankReview;
    @Autowired com.huaneng.shanyin.hnexam.service.ExamExpiryScheduler expiryScheduler;
    @Autowired ExamRepository exams;
    @Autowired ExamAttemptRepository attempts;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PracticeController practice;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired com.huaneng.shanyin.hnexam.service.WrongQuestionService wrong;
    @Autowired com.huaneng.shanyin.hnexam.service.PracticeTimeService time;
    @Autowired com.huaneng.shanyin.hnexam.controller.ProfileController profiles;
    @Autowired com.huaneng.shanyin.hnexam.service.OpenAiCompatibleGradingProvider ai;


    @Test void publishedSnapshotAutosaveResumeAndIdempotentSubmission() {
        AppUser user = user(); Question q = question(QuestionType.SINGLE_CHOICE);
        Long examId = publish(q, true, 2);
        q.setStem("修改后的题目"); q.setAnswer("B"); questions.save(q);
        var start = service.start(examId, user.getEmployeeNo());
        assertThat(start.questions().get(0).stem()).isEqualTo("测试题");
        service.saveAnswers(start.attemptId(), user.getEmployeeNo(), answers(q, "A"));
        var resumed = service.start(examId, user.getEmployeeNo());
        assertThat(resumed.attemptId()).isEqualTo(start.attemptId());
        assertThat(resumed.questions().get(0).savedAnswer()).isEqualTo("A");
        assertThat(service.submit(start.attemptId(), user.getEmployeeNo(), answers(q, "A")).score()).isEqualTo(100);
        assertThat(service.submit(start.attemptId(), user.getEmployeeNo(), answers(q, "B")).score()).isEqualTo(100);
        assertThat(service.review(start.attemptId(), user.getEmployeeNo()).questions().get(0).correctAnswer()).isEqualTo("A");
        assertThatThrownBy(() -> service.start(examId, user.getEmployeeNo())).hasMessageContaining("已通过");
        assertThat(service.statistics(examId).passedCount()).isEqualTo(1);
    }

    @Test void serverDeadlineGradesSavedAnswersAndDiscardsLateAnswers() {
        AppUser user = user(); Question q = question(QuestionType.SINGLE_CHOICE);
        var a = service.start(publish(q, false, 1), user.getEmployeeNo());
        service.saveAnswers(a.attemptId(), user.getEmployeeNo(), answers(q, "B"));
        tx.executeWithoutResult(status -> attempts.findLockedById(a.attemptId()).orElseThrow().setDeadline(Instant.now().minusSeconds(5)));
        var result = service.submit(a.attemptId(), user.getEmployeeNo(), answers(q, "A"));
        assertThat(result.score()).isZero();
        assertThat(service.review(a.attemptId(), user.getEmployeeNo()).questions().get(0).savedAnswer()).isEqualTo("B");
    }

    @Test void schedulerGradesEvenWhenBrowserNeverReconnects() {
        AppUser user = user(); Question q = question(QuestionType.SINGLE_CHOICE);
        var a = service.start(publish(q, false, 1), user.getEmployeeNo());
        service.saveAnswers(a.attemptId(), user.getEmployeeNo(), answers(q, "A"));
        tx.executeWithoutResult(status -> attempts.findLockedById(a.attemptId()).orElseThrow().setDeadline(Instant.now().minusSeconds(5)));
        expiryScheduler.autoSubmitExpired();
        assertThat(attempts.findById(a.attemptId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.GRADED);
        assertThat(attempts.findById(a.attemptId()).orElseThrow().getScore()).isEqualTo(100);
    }

    @Test void retakeCountAndClosingAreEnforced() {
        AppUser user = user(); Question q = question(QuestionType.SINGLE_CHOICE);
        AppUser other = user();
        Long id = publish(q, true, 2);
        var first = service.start(id, user.getEmployeeNo());
        service.submit(first.attemptId(), user.getEmployeeNo(), answers(q, "B"));
        assertThatThrownBy(() -> service.start(id, user.getEmployeeNo())).hasMessageContaining("只能参加一次");
        Long makeup = service.createMakeup(id).id();
        assertThat(service.detail(makeup).audienceUserIds()).containsExactly(user.getId());
        assertThat(service.detail(makeup).makeupSourceExamId()).isEqualTo(id);
        service.publish(makeup);
        var second = service.start(makeup, user.getEmployeeNo());
        service.submit(second.attemptId(), user.getEmployeeNo(), answers(q, "A"));
        assertThatThrownBy(() -> service.start(makeup, user().getEmployeeNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var pending = service.start(id, other.getEmployeeNo());
        service.close(id);
        assertThat(service.getAttempt(pending.attemptId(), other.getEmployeeNo()).status()).isEqualTo(AttemptStatus.GRADED);
        assertThatThrownBy(() -> service.publish(id)).hasMessageContaining("草稿");
    }

    @Test void draftUpdateDoesNotViolateUniqueQuestionConstraintAndSubjectiveTypesAreAccepted() {
        Question q = question(QuestionType.SINGLE_CHOICE);
        var draft = service.create(request(q, false, 1));
        service.update(draft.id(), request(q, false, 1));
        assertThat(service.detail(draft.id()).questions()).hasSize(1);
        Question fill = question(QuestionType.FILL_BLANK);
        assertThat(service.create(request(fill, false, 1)).questionCount()).isEqualTo(1);
        q.setStatus(QuestionStatus.PENDING_REVIEW); questions.save(q);
        assertThatThrownBy(() -> service.publish(draft.id())).hasMessageContaining("启用");
    }

    @Test void subjectiveAnswersWaitForSystemAdministratorAndBecomeFinalAfterManualGrading() {
        AppUser user = user();
        Question objective = question(QuestionType.SINGLE_CHOICE);
        Question subjective = question(QuestionType.SHORT_ANSWER);
        subjective.setOptionsJson(null); subjective.setAnswer("应包含的评分要点"); questions.save(subjective);
        var request = new ExamDtos.SaveRequest("主观题考试", "TEST", List.of(), "人工判卷测试", 90, 100, 80,
                null, null, false, 1, false, true, false, 20, List.of(
                new ExamDtos.QuestionItem(objective.getId(), 40, 1),
                new ExamDtos.QuestionItem(subjective.getId(), 60, 2)));
        Long examId = service.create(request).id(); service.publish(examId);
        var started = service.start(examId, user.getEmployeeNo());
        var submitted = service.submit(started.attemptId(), user.getEmployeeNo(),
                new ExamDtos.SubmitRequest(Map.of(objective.getId(), "A", subjective.getId(), "考生的简答内容"), 0));
        assertThat(submitted.status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(submitted.score()).isNull();
        assertThat(submitted.pendingSubjectiveCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.start(examId, user.getEmployeeNo())).hasMessageContaining("正在判卷");
        assertThatThrownBy(() -> service.review(started.attemptId(), user.getEmployeeNo())).hasMessageContaining("人工判卷");
        assertThat(service.review(started.attemptId(), null).questions()).filteredOn(q -> !q.objective())
                .singleElement().satisfies(q -> assertThat(q.savedAnswer()).isEqualTo("考生的简答内容"));
        assertThatThrownBy(() -> service.manualGrade(started.attemptId(), "admin",
                new ExamDtos.ManualGradeRequest(Map.of(subjective.getId(), new ExamDtos.ManualScore(61, "超分")), null)))
                .hasMessageContaining("不能超过");
        var graded = service.manualGrade(started.attemptId(), "admin",
                new ExamDtos.ManualGradeRequest(Map.of(subjective.getId(), new ExamDtos.ManualScore(45, "要点基本完整", 75.0)), "人工复核完成"));
        assertThat(graded.grade().status()).isEqualTo(AttemptStatus.GRADED);
        assertThat(graded.grade().score()).isEqualTo(85);
        assertThat(graded.grade().passed()).isTrue();
        assertThat(graded.grade().gradedByEmployeeNo()).isEqualTo("admin");
        assertThat(graded.questions()).filteredOn(q -> !q.objective()).singleElement().satisfies(q -> {
            assertThat(q.awardedPoints()).isEqualTo(45);
            assertThat(q.gradingMethod()).isEqualTo(GradingMethod.MANUAL);
            assertThat(q.gradingComment()).isEqualTo("要点基本完整");
        });
    }

    @Test void answersArePrivateAndCannotBeViewedBeforeSubmission() throws Exception {
        AppUser user = user(); AppUser other = user(); Question q = question(QuestionType.SINGLE_CHOICE);
        var a = service.start(publish(q, false, 1), user.getEmployeeNo());
        mvc.perform(get("/api/candidate/attempts/" + a.attemptId()).with(jwt().jwt(j -> j.subject(user.getEmployeeNo())).authorities(new SimpleGrantedAuthority("CANDIDATE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].answer").doesNotExist())
                .andExpect(jsonPath("$.questions[0].correctAnswer").doesNotExist());
        mvc.perform(get("/api/candidate/attempts/" + a.attemptId() + "/review").with(jwt().jwt(j -> j.subject(user.getEmployeeNo())).authorities(new SimpleGrantedAuthority("CANDIDATE"))))
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> service.getAttempt(a.attemptId(), other.getEmployeeNo())).hasMessageContaining("不存在");
        mvc.perform(get("/api/exams").with(jwt().jwt(j -> j.subject(user.getEmployeeNo())).authorities(new SimpleGrantedAuthority("CANDIDATE"))))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(authorities = "CANDIDATE")
    void twoConsecutivePracticeAnswersClearDailyButNeverExamWrongAnswers() {
        AppUser user = user(); Question q = question(QuestionType.SINGLE_CHOICE);
        Jwt token = Jwt.withTokenValue("test").header("alg", "HS256").subject(user.getEmployeeNo()).build();
        var a = service.start(publish(q, false, 1), user.getEmployeeNo());
        assertThatThrownBy(() -> practice.submit(q.getId(), new QuestionDtos.PracticeSubmit("A"), token)).hasMessageContaining("考试进行中");
        service.submit(a.attemptId(), user.getEmployeeNo(), answers(q, "B"));
        assertThat(practice.examWrong(token)).extracting(QuestionDtos.CandidateView::id).contains(q.getId());
        assertThat(practice.dailyWrong(token)).extracting(QuestionDtos.CandidateView::id).doesNotContain(q.getId());
        practice.submit(q.getId(), new QuestionDtos.PracticeSubmit("B"), token);
        practice.submit(q.getId(), new QuestionDtos.PracticeSubmit("A"), token);
        assertThat(practice.dailyWrong(token)).extracting(QuestionDtos.CandidateView::id).contains(q.getId());
        practice.submit(q.getId(), new QuestionDtos.PracticeSubmit("A"), token);
        assertThat(practice.dailyWrong(token)).extracting(QuestionDtos.CandidateView::id).doesNotContain(q.getId());
        assertThat(practice.examWrong(token)).extracting(QuestionDtos.CandidateView::id).contains(q.getId());
    }

    @Test @WithMockUser(authorities = "CANDIDATE")
    void publishesAnIndependentPaperFromEachCandidatesUnresolvedExamWrongQuestions() {
        AppUser user = user(); Question q = question(QuestionType.SINGLE_CHOICE); q.setChapter("第一章"); questions.save(q);
        Jwt token = Jwt.withTokenValue("test").header("alg", "HS256").subject(user.getEmployeeNo()).build();
        var original = service.start(publish(q, false, 1), user.getEmployeeNo());
        service.submit(original.attemptId(), user.getEmployeeNo(), answers(q, "B"));
        assertThat(practice.examWrong(token)).extracting(QuestionDtos.CandidateView::id).contains(q.getId());

        var request = new ExamDtos.SaveRequest("个人错题复训", "TEST", List.of("第一章"), "自动抽取考试错题", 30, 100, 90,
                null, null, false, 1, true, true, true, 10, List.of());
        Long examId = service.create(request).id();
        assertThat(service.publish(examId).assignedCandidateCount()).isGreaterThanOrEqualTo(1);
        var retry = service.start(examId, user.getEmployeeNo());
        assertThat(retry.questions()).extracting(ExamDtos.AttemptQuestion::id).containsExactly(q.getId());
        service.submit(retry.attemptId(), user.getEmployeeNo(), answers(q, "A"));
        assertThat(practice.examWrong(token)).extracting(QuestionDtos.CandidateView::id).doesNotContain(q.getId());
    }

    @Test void simultaneousStartAndSubmitCreateOneAttemptAndOneGrade() throws Exception {
        AppUser user = user(); Question q = question(QuestionType.SINGLE_CHOICE);
        Long id = publish(q, true, 2);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> starts = new ArrayList<>();
            for (int i = 0; i < 4; i++) starts.add(() -> service.start(id, user.getEmployeeNo()).attemptId());
            Set<Long> ids = new HashSet<>();
            for (Future<Long> result : pool.invokeAll(starts)) ids.add(result.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            Long attemptId = ids.iterator().next();
            List<Callable<Integer>> submits = new ArrayList<>();
            for (int i = 0; i < 4; i++) submits.add(() -> service.submit(attemptId, user.getEmployeeNo(), answers(q, "A")).score());
            for (Future<Integer> result : pool.invokeAll(submits)) assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(100);
            assertThat(service.review(attemptId, user.getEmployeeNo()).questions()).hasSize(1);
            assertThat(attempts.countByExamIdAndUserId(id, user.getId())).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void twoHundredCandidatesCanStartAutosaveAndSubmitConcurrently() throws Exception {
        Question q = question(QuestionType.SINGLE_CHOICE);
        List<AppUser> candidates = new ArrayList<>();
        for (int i = 0; i < 200; i++) candidates.add(user());
        Long examId = publish(q, false, 1);
        ExecutorService pool = Executors.newFixedThreadPool(40);
        long started = System.nanoTime();
        try {
            List<Callable<Integer>> tasks = candidates.stream().<Callable<Integer>>map(candidate -> () -> {
                var attempt = service.start(examId, candidate.getEmployeeNo());
                var saved = service.saveAnswers(attempt.attemptId(), candidate.getEmployeeNo(), answers(q, "A"));
                assertThat(saved.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
                return service.submit(attempt.attemptId(), candidate.getEmployeeNo(), answers(q, "A")).score();
            }).toList();
            List<Future<Integer>> results = pool.invokeAll(tasks);
            for (Future<Integer> result : results) assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(100);
            assertThat(attempts.findByExamIdOrderByStartedAtDesc(examId)).hasSize(200);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(30);
        } finally { pool.shutdownNow(); }
    }

    @Test void secondFocusLossSavesCurrentAnswerAndForcesSubmission() {
        var u=user(); var q=question(QuestionType.SINGLE_CHOICE); var a=service.start(publish(q,false,1),u.getEmployeeNo());
        assertThat(service.saveAnswers(a.attemptId(),u.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(q.getId(),"B"),1)).status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        var ack=service.saveAnswers(a.attemptId(),u.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(q.getId(),"A"),2));
        assertThat(ack.status()).isEqualTo(AttemptStatus.GRADED);
        assertThat(service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"B")).score()).isEqualTo(100);
        assertThat(service.getAttempt(a.attemptId(),u.getEmployeeNo()).focusLossCount()).isEqualTo(2);
    }
    @Test @WithMockUser(authorities="CANDIDATE") void similarityBoundaryAndPersonalSubjectivePaperKeepScoresIndependent() {
        var u=user();var q=question(QuestionType.SHORT_ANSWER);var token=Jwt.withTokenValue("test").header("alg","HS256").subject(u.getEmployeeNo()).build();
        var original=service.start(publish(q,false,1),u.getEmployeeNo());service.submit(original.attemptId(),u.getEmployeeNo(),answers(q,"语义不同"));
        assertThatThrownBy(()->service.manualGrade(original.attemptId(),"admin",new ExamDtos.ManualGradeRequest(Map.of(q.getId(),new ExamDtos.ManualScore(90,"缺相似度")),null))).hasMessageContaining("相似度");
        service.manualGrade(original.attemptId(),"admin",new ExamDtos.ManualGradeRequest(Map.of(q.getId(),new ExamDtos.ManualScore(90,"分别评估",29.9)),null));
        assertThat(wrong.unresolved(u.getId(),null)).extracting(Question::getId).contains(q.getId());
        var req=new ExamDtos.SaveRequest("错题主观题", "TEST",List.of(),"",30,100,90,null,null,false,1,false,true,true,10,List.of());
        Long id=service.create(req).id();service.publish(id);var retry=service.start(id,u.getEmployeeNo());
        assertThat(retry.questions()).extracting(ExamDtos.AttemptQuestion::id).contains(q.getId());
        var submitted=service.submit(retry.attemptId(),u.getEmployeeNo(),answers(q,"新作答"));assertThat(submitted.pendingSubjectiveCount()).isEqualTo(1);
        var result=service.manualGrade(retry.attemptId(),"admin",new ExamDtos.ManualGradeRequest(Map.of(q.getId(),new ExamDtos.ManualScore(20,"语义达标，但要点得分20",30.0)),null));
        assertThat(result.grade().score()).isEqualTo(20);assertThat(result.grade().passed()).isFalse();assertThat(result.grade().pendingSubjectiveCount()).isZero();
        assertThat(wrong.unresolved(u.getId(),null)).isEmpty();assertThat(wrong.summary(u.getId()).resolutionRate()).isEqualTo(100);
        assertThat(practice.submit(q.getId(),new QuestionDtos.PracticeSubmit("自行复习"),token).correct()).isNull();
        assertThat(wrong.unresolved(u.getId(),null)).isEmpty();
    }
    @Test @WithMockUser(authorities="CANDIDATE") void ordinaryCorrectExamsNeverClearWrongAndDailyOnlyWrongIsExcludedFromPersonalPaper() {
        var u=user();var q=question(QuestionType.SINGLE_CHOICE);var token=Jwt.withTokenValue("test").header("alg","HS256").subject(u.getEmployeeNo()).build();
        practice.submit(q.getId(),new QuestionDtos.PracticeSubmit("B"),token);
        var normal=service.start(publish(q,false,1),u.getEmployeeNo());service.submit(normal.attemptId(),u.getEmployeeNo(),answers(q,"A"));
        assertThat(wrong.unresolved(u.getId(),null)).hasSize(1);
        var req=new ExamDtos.SaveRequest("日常错题复训", "TEST",List.of(),"",30,1,1,null,null,false,1,false,true,true,200,List.of());
        Long id=service.create(req).id();
        // Other test candidates may have exam wrong answers, but this learner has only a daily wrong answer.
        try { service.publish(id); } catch (IllegalStateException ignored) { }
        assertThat(service.candidateList(u.getEmployeeNo())).extracting(ExamDtos.Summary::id).doesNotContain(id);
        assertThat(wrong.unresolved(u.getId(),"EXAM")).isEmpty();
        assertThat(wrong.unresolved(u.getId(),"DAILY")).extracting(Question::getId).contains(q.getId());
    }
    @Test void cancelsInProgressWithoutGradingOrCountingAndRejectsNewStarts() {
        var notStarted = user();
        var u=user();var q=question(QuestionType.SINGLE_CHOICE);Long id=publish(q,false,1);var a=service.start(id,u.getEmployeeNo());
        service.saveAnswers(a.attemptId(),u.getEmployeeNo(),answers(q,"A"));service.cancel(id,"临时调整","admin");
        assertThat(service.getAttempt(a.attemptId(),u.getEmployeeNo()).status()).isEqualTo(AttemptStatus.CANCELLED);
        assertThat(service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"B")).score()).isNull();
        assertThat(service.statistics(id).submittedCount()).isZero();assertThat(wrong.unresolved(u.getId(),null)).isEmpty();
        assertThatThrownBy(()->service.start(id,notStarted.getEmployeeNo())).hasMessageContaining("取消");
    }
    @Test void scoreDistributionUsesFiveHalfOpenNormalizedBandsAndKeepsZero() {
        var q=question(QuestionType.CALCULATION);var req=new ExamDtos.SaveRequest("区间", "TEST",List.of(),"",30,200,120,null,null,false,1,false,true,false,10,List.of(new ExamDtos.QuestionItem(q.getId(),200,1)));
        var learners = java.util.stream.IntStream.range(0, 10).mapToObj(i -> user()).toList().iterator();
        Long id=service.create(req).id();service.publish(id);
        for(int score: new int[]{0,118,120,138,140,158,160,178,180,200}){var u=learners.next();var a=service.start(id,u.getEmployeeNo());service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"作答"));service.manualGrade(a.attemptId(),"admin",new ExamDtos.ManualGradeRequest(Map.of(q.getId(),new ExamDtos.ManualScore(score,"测试")),null));}
        var stats=service.statistics(id);assertThat(stats.scoreBands()).extracting(ExamDtos.ScoreBand::count).containsExactly(2L,2L,2L,2L,2L);assertThat(stats.lowestScore()).isZero();
    }
    @Test void heartbeatClampsIdleGapsAndDoesNotDoubleCountTabs() {
        var u=user();var t=Instant.parse("2026-10-07T08:00:00Z");
        time.heartbeatAt(u.getEmployeeNo(),"tab1",true,0,t);
        assertThat(time.heartbeatAt(u.getEmployeeNo(),"tab1",true,10,t.plusSeconds(10)).totalSeconds()).isEqualTo(10);
        assertThat(time.heartbeatAt(u.getEmployeeNo(),"tab2",true,0,t.plusSeconds(10)).recording()).isFalse();
        assertThat(time.heartbeatAt(u.getEmployeeNo(),"tab1",true,10,t.plusSeconds(10)).totalSeconds()).isEqualTo(10);
        assertThat(time.heartbeatAt(u.getEmployeeNo(),"tab1",true,120,t.plusSeconds(130)).totalSeconds()).isEqualTo(10);
        assertThat(time.heartbeatAt(u.getEmployeeNo(),"tab1",true,0,t.plusSeconds(140)).totalSeconds()).isEqualTo(10);
        assertThat(time.heartbeatAt(u.getEmployeeNo(),"tab1",false,0,t.plusSeconds(150)).totalSeconds()).isEqualTo(20);
    }
    @Test @WithMockUser(authorities="SYSTEM_ADMIN") void manualRetakeIsAuditedIdempotentAndSeparateFromWrongResolution() {
        var u=user();var q=question(QuestionType.SINGLE_CHOICE);var a=service.start(publish(q,false,1),u.getEmployeeNo());service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"B"));
        var jwt=Jwt.withTokenValue("test").header("alg","HS256").subject("admin").build();
        var r=new com.huaneng.shanyin.hnexam.controller.ProfileController.Manual("纸质补考","TEST",95,100,90,java.time.LocalDate.of(2026,10,7),"依据纸质签字成绩单",UUID.randomUUID().toString());
        var first=profiles.add(u.getId(),r,jwt);assertThat(profiles.add(u.getId(),r,jwt).id()).isEqualTo(first.id());
        assertThat(first.recordedBy()).isEqualTo("admin");assertThat(first.source()).isEqualTo("MANUAL_RETAKE");assertThat(wrong.unresolved(u.getId(),null)).hasSize(1);
        profiles.update(u.getId(),new com.huaneng.shanyin.hnexam.controller.ProfileController.Fields("运行","电气","值班员",java.time.LocalDate.of(2026,9,1)));
        assertThat(profiles.get(u.getId()).person().position()).isEqualTo("值班员");assertThat(profiles.get(u.getId()).grades()).hasSize(2);
    }
    @Test void aiDisabledAndStrictValidationNeverPublishScores() throws Exception {
        assertThat(ai.available()).isFalse();
        var valid=ai.parseSuggestion("{\"points\":20,\"semanticSimilarity\":70,\"reason\":\"要点不全\"}",60,"test-model");assertThat(valid.points()).isEqualTo(20);assertThat(valid.semanticSimilarity()).isEqualTo(70);
        assertThatThrownBy(()->ai.parseSuggestion("{\"points\":61,\"semanticSimilarity\":30,\"reason\":\"错误\"}",60,"test")).hasMessageContaining("越界");
        assertThatThrownBy(()->ai.parseSuggestion("{\"points\":20.5,\"semanticSimilarity\":30,\"reason\":\"错误\"}",60,"test")).hasMessageContaining("格式");
    }

    @Test void candidatesReceiveDifferentFrozenPapersAndCannotReadEachOther() {
        var one=user();var two=user();var empty=user();var q1=question(QuestionType.SINGLE_CHOICE);var q2=question(QuestionType.SINGLE_CHOICE);
        String course="PRIVATE-"+UUID.randomUUID();q1.setCourseCode(course);q2.setCourseCode(course);questions.saveAll(List.of(q1,q2));
        Long originalId=service.create(new ExamDtos.SaveRequest("来源考试",course,List.of(),"",30,100,90,null,null,false,1,false,true,false,20,List.of(new ExamDtos.QuestionItem(q1.getId(),50,1),new ExamDtos.QuestionItem(q2.getId(),50,2)))).id();service.publish(originalId);
        var a=service.start(originalId,one.getEmployeeNo());service.submit(a.attemptId(),one.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(q1.getId(),"B",q2.getId(),"A"),0));
        var b=service.start(originalId,two.getEmployeeNo());service.submit(b.attemptId(),two.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(q1.getId(),"A",q2.getId(),"B"),0));
        Long id=service.create(new ExamDtos.SaveRequest("个人卷",course,List.of(),"",30,100,90,null,null,false,1,false,true,true,20,List.of())).id();assertThat(service.publish(id).assignedCandidateCount()).isEqualTo(2);
        assertThatThrownBy(()->service.publish(id)).hasMessageContaining("草稿");
        var first=service.start(id,one.getEmployeeNo());var second=service.start(id,two.getEmployeeNo());
        assertThat(first.questions()).extracting(ExamDtos.AttemptQuestion::id).containsExactly(q1.getId());assertThat(second.questions()).extracting(ExamDtos.AttemptQuestion::id).containsExactly(q2.getId());
        assertThatThrownBy(()->service.getAttempt(second.attemptId(),one.getEmployeeNo())).hasMessageContaining("不存在");
        assertThatThrownBy(()->service.saveAnswers(first.attemptId(),one.getEmployeeNo(),answers(q2,"A"))).hasMessageContaining("非本试卷");
        assertThat(service.candidateList(empty.getEmployeeNo())).extracting(ExamDtos.Summary::id).doesNotContain(id);
    }
    @Test void scheduleUsesServerTimeAndDoesNotCreateAttemptsBeforeStart() {
        var u=user();var q=question(QuestionType.SINGLE_CHOICE);var request=new ExamDtos.SaveRequest("定时", "TEST",List.of(),"",30,100,90,Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),false,1,false,true,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),100,1)));
        Long id=service.create(request).id();service.publish(id);assertThat(service.candidateList(u.getEmployeeNo())).extracting(ExamDtos.Summary::id).contains(id);
        assertThatThrownBy(()->service.start(id,u.getEmployeeNo())).hasMessageContaining("尚未开始");assertThat(attempts.countByExamIdAndUserId(id,u.getId())).isZero();
        tx.executeWithoutResult(t->exams.findById(id).orElseThrow().setStartTime(Instant.now().minusSeconds(1)));
        assertThat(service.start(id,u.getEmployeeNo()).status()).isEqualTo(AttemptStatus.IN_PROGRESS);
    }
    @Test void idleHeartbeatCreditsOnlyFirstTwoMinutesAndSplitsLocalMidnight() {
        var u=user();var t=Instant.parse("2026-10-07T15:59:30Z");time.heartbeatAt(u.getEmployeeNo(),"tab",true,0,t);
        for(int second=10;second<=140;second+=10) time.heartbeatAt(u.getEmployeeNo(),"tab",true,Math.min(120,second),t.plusSeconds(second));
        var summary=time.heartbeatAt(u.getEmployeeNo(),"tab",true,120,t.plusSeconds(150));assertThat(summary.totalSeconds()).isEqualTo(120);assertThat(summary.todaySeconds()).isEqualTo(90);assertThat(summary.recording()).isFalse();
    }
    @Test void cancelledPublishedGradesStayStoredButAreExcludedEverywhere() {
        var u=user();var q=question(QuestionType.SINGLE_CHOICE);Long id=publish(q,false,1);var a=service.start(id,u.getEmployeeNo());service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"B"));
        assertThat(wrong.summary(u.getId()).entered()).isEqualTo(1);service.cancel(id,"取消已完成考试","admin");assertThat(attempts.findById(a.attemptId()).orElseThrow().getScore()).isZero();
        assertThat(service.grades(u.getEmployeeNo()).get(0).status()).isEqualTo(AttemptStatus.CANCELLED);assertThat(service.statistics(id).highErrorQuestions()).isEmpty();assertThat(wrong.summary(u.getId()).entered()).isZero();
    }

    @Test void expiryQueryExcludesLivePapersAndKeepsLegacyAndClosedPapers() {
        var q = question(QuestionType.SINGLE_CHOICE);
        var liveUser = user(); var dueUser = user(); var legacyUser = user(); var closedUser = user();
        Long exam = publish(q, false, 1);
        var live = service.start(exam, liveUser.getEmployeeNo());
        var due = service.start(exam, dueUser.getEmployeeNo());
        var legacy = service.start(exam, legacyUser.getEmployeeNo());
        Long closedExam = publish(q, false, 1);
        var closed = service.start(closedExam, closedUser.getEmployeeNo());
        tx.executeWithoutResult(s -> {
            attempts.findById(due.attemptId()).orElseThrow().setDeadline(Instant.now().minusSeconds(1));
            attempts.findById(legacy.attemptId()).orElseThrow().setDeadline(null);
            exams.findById(closedExam).orElseThrow().setStatus(ExamStatus.CLOSED);
        });
        var selected = attempts.findExpiryCandidateIds(Instant.now(), 0L, org.springframework.data.domain.PageRequest.of(0, 500));
        assertThat(selected).contains(due.attemptId(), legacy.attemptId(), closed.attemptId()).doesNotContain(live.attemptId());
        service.expireAttempt(legacy.attemptId());
        assertThat(attempts.findById(legacy.attemptId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
        tx.executeWithoutResult(s -> attempts.findById(legacy.attemptId()).orElseThrow().setStartedAt(Instant.now().minusSeconds(7200)));
        service.expireAttempt(legacy.attemptId());
        service.expireAttempt(closed.attemptId());
        assertThat(attempts.findById(legacy.attemptId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.GRADED);
        assertThat(attempts.findById(closed.attemptId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.GRADED);
    }

    @Test void expiryDoesNotWaitForAnUnexpiredPaperLock() throws Exception {
        var q = question(QuestionType.SINGLE_CHOICE);
        var liveUser = user(); var candidate = user();
        Long exam = publish(q, false, 1);
        var live = service.start(exam, liveUser.getEmployeeNo());
        var due = service.start(exam, candidate.getEmployeeNo());
        service.saveAnswers(due.attemptId(), candidate.getEmployeeNo(), answers(q, "A"));
        tx.executeWithoutResult(s -> attempts.findById(due.attemptId()).orElseThrow().setDeadline(Instant.now().minusSeconds(1)));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            attempts.findLockedById(live.attemptId()).orElseThrow();
            locked.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }));
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(expiryScheduler::autoSubmitExpired).get(3, TimeUnit.SECONDS);
            assertThat(attempts.findById(due.attemptId()).orElseThrow().getScore()).isEqualTo(100);
        } finally { release.countDown(); holder.get(5, TimeUnit.SECONDS); pool.shutdownNow(); }
    }

    @Test void expiryCommitSurvivesAnOuterTransactionRollback() {
        var q = question(QuestionType.SINGLE_CHOICE); var candidate = user();
        var a = service.start(publish(q, false, 1), candidate.getEmployeeNo());
        service.saveAnswers(a.attemptId(), candidate.getEmployeeNo(), answers(q, "A"));
        tx.executeWithoutResult(s -> attempts.findById(a.attemptId()).orElseThrow().setDeadline(Instant.now().minusSeconds(1)));
        tx.executeWithoutResult(s -> { service.expireAttempt(a.attemptId()); s.setRollbackOnly(); });
        assertThat(attempts.findById(a.attemptId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.GRADED);
        assertThat(attempts.findById(a.attemptId()).orElseThrow().getScore()).isEqualTo(100);
    }

    @Test void racingLateSubmissionCannotOverwriteExpiredAnswers() throws Exception {
        var q = question(QuestionType.SINGLE_CHOICE); var candidate = user();
        var a = service.start(publish(q, false, 1), candidate.getEmployeeNo());
        service.saveAnswers(a.attemptId(), candidate.getEmployeeNo(), answers(q, "A"));
        tx.executeWithoutResult(s -> attempts.findById(a.attemptId()).orElseThrow().setDeadline(Instant.now().minusSeconds(1)));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var expiry = pool.submit(() -> tx.executeWithoutResult(s -> { service.expireAttempt(a.attemptId()); s.setRollbackOnly(); }));
            var submission = pool.submit(() -> service.submit(a.attemptId(), candidate.getEmployeeNo(), answers(q, "B")));
            expiry.get(5, TimeUnit.SECONDS); submission.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(service.review(a.attemptId(), candidate.getEmployeeNo()).questions().get(0).savedAnswer()).isEqualTo("A");
        assertThat(attempts.findById(a.attemptId()).orElseThrow().getScore()).isEqualTo(100);
    }

    @Test void learnerDashboardWithExamHistoryWorksWithoutAnOpenPersistenceSession() throws Exception {
        AppUser candidate = user();
        AppUser other = user();
        var q = question(QuestionType.SINGLE_CHOICE);
        Long activeExam = publish(q, true, 3);
        Long cancelledExam = publish(q, false, 1);
        tx.executeWithoutResult(s -> exams.findById(cancelledExam).orElseThrow().setStatus(ExamStatus.CANCELLED));
        dashboardAttempt(candidate, activeExam, AttemptStatus.GRADED, true);
        dashboardAttempt(candidate, activeExam, AttemptStatus.GRADED, true);
        dashboardAttempt(candidate, activeExam, AttemptStatus.GRADED, false);
        dashboardAttempt(candidate, activeExam, AttemptStatus.SUBMITTED, null);
        dashboardAttempt(candidate, activeExam, AttemptStatus.IN_PROGRESS, null);
        dashboardAttempt(candidate, cancelledExam, AttemptStatus.GRADED, true);
        dashboardAttempt(other, activeExam, AttemptStatus.GRADED, true);

        // No test transaction: exercise the same closed-session boundary as the real HTTP request.
        mvc.perform(get("/api/dashboard/summary").with(jwt()
                        .jwt(j -> j.subject(candidate.getEmployeeNo()))
                        .authorities(new SimpleGrantedAuthority("ROLE_CANDIDATE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("examCount").value(3))
                .andExpect(jsonPath("passedCount").value(1))
                .andExpect(jsonPath("practiceCount").value(0))
                .andExpect(jsonPath("correctCount").value(0));
    }

    @Test void learnerDashboardWithoutExamHistoryReturnsZeroCounts() throws Exception {
        var candidate = user();
        mvc.perform(get("/api/dashboard/summary").with(jwt()
                        .jwt(j -> j.subject(candidate.getEmployeeNo()))
                        .authorities(new SimpleGrantedAuthority("ROLE_CANDIDATE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("examCount").value(0))
                .andExpect(jsonPath("passedCount").value(0));
    }

    private void dashboardAttempt(AppUser user, Long examId, AttemptStatus status, Boolean passed) {
        var attempt = new ExamAttempt();
        attempt.setUser(user);
        attempt.setExam(exams.findById(examId).orElseThrow());
        attempt.setStartedAt(Instant.now());
        attempt.setDeadline(Instant.now().plusSeconds(3600));
        attempt.setStatus(status);
        attempt.setPassed(passed);
        attempts.save(attempt);
    }

    @Test void fillBlankSubmissionUsesSnapshotPartialPointsAndKeepsSubmissionIdempotent() {
        var q = fillQuestion(); var candidate = user(); Long examId = publishFill(q, 10);
        q.setAnswer("已改题库答案"); questions.save(q);
        var attempt = service.start(examId, candidate.getEmployeeNo());
        var grade = service.submit(attempt.attemptId(), candidate.getEmployeeNo(), answers(q, "人身；；设备"));
        assertThat(grade.status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(grade.score()).isNull();
        grade = publishBlankAttempt(attempt.attemptId());
        assertThat(grade.score()).isEqualTo(7);
        assertThat(grade.subjectiveQuestionCount()).isZero();
        assertThat(grade.pendingSubjectiveCount()).isZero();
        assertThat(service.review(attempt.attemptId(), candidate.getEmployeeNo()).questions()).singleElement().satisfies(item -> {
            assertThat(item.objective()).isTrue(); assertThat(item.correct()).isFalse();
            assertThat(item.awardedPoints()).isEqualTo(7);
            assertThat(item.gradingMethod()).isEqualTo(GradingMethod.AUTO);
            assertThat(item.gradingComment()).contains("共 3 空，答对 2 空");
        });
        assertThat(service.submit(attempt.attemptId(), candidate.getEmployeeNo(), answers(q, "人身；电网；设备")).score()).isEqualTo(7);
        assertThat(wrong.unresolved(candidate.getId(), "EXAM")).extracting(Question::getId).contains(q.getId());
    }

    @Test void fullAndUnansweredFillBlanksAreAutomaticallyFinalized() {
        var fullUser = user(); var emptyUser = user();
        var q = fillQuestion(); Long examId = publishFill(q, 10);
        var full = service.start(examId, fullUser.getEmployeeNo());
        assertThat(service.submit(full.attemptId(), fullUser.getEmployeeNo(), answers(q, "人身,电网,设备")).score()).isNull();
        assertThat(publishBlankAttempt(full.attemptId()).score()).isEqualTo(10);
        assertThat(service.review(full.attemptId(), fullUser.getEmployeeNo()).questions().get(0).correct()).isTrue();
        var empty = service.start(examId, emptyUser.getEmployeeNo());
        var grade = service.submit(empty.attemptId(), emptyUser.getEmployeeNo(), new ExamDtos.SubmitRequest(Map.of(), 0));
        assertThat(grade.status()).isEqualTo(AttemptStatus.SUBMITTED); assertThat(grade.score()).isNull();
        assertThat(publishBlankAttempt(empty.attemptId()).score()).isZero();
    }

    @Test void expiredFillBlankPaperUsesSavedPartialAnswers() {
        var q = fillQuestion(); var candidate = user(); var attempt = service.start(publishFill(q, 10), candidate.getEmployeeNo());
        service.saveAnswers(attempt.attemptId(), candidate.getEmployeeNo(), answers(q, "人身；；设备"));
        tx.executeWithoutResult(s -> attempts.findById(attempt.attemptId()).orElseThrow().setDeadline(Instant.now().minusSeconds(1)));
        service.expireAttempt(attempt.attemptId());
        assertThat(publishBlankAttempt(attempt.attemptId()).score()).isEqualTo(7);
    }

    @Test void mixedPaperKeepsFillBlankPointsWhenManualGradingFinishes() {
        var fill = fillQuestion(); var manual = question(QuestionType.CALCULATION); var candidate = user();
        var request = new ExamDtos.SaveRequest("混合判卷", "TEST", List.of(), "", 30, 20, 12,
                null, null, false, 1, false, false, false, 10, List.of(
                new ExamDtos.QuestionItem(fill.getId(), 10, 1), new ExamDtos.QuestionItem(manual.getId(), 10, 2)));
        Long examId = service.create(request).id(); service.publish(examId);
        var attempt = service.start(examId, candidate.getEmployeeNo());
        var grade = service.submit(attempt.attemptId(), candidate.getEmployeeNo(), new ExamDtos.SubmitRequest(
                Map.of(fill.getId(), "人身；；设备", manual.getId(), "计算步骤"), 0));
        assertThat(grade.status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(grade.pendingSubjectiveCount()).isEqualTo(1);
        var reviewed = service.manualGrade(attempt.attemptId(), "admin", new ExamDtos.ManualGradeRequest(
                Map.of(manual.getId(), new ExamDtos.ManualScore(8, "步骤正确")), null));
        assertThat(reviewed.grade().status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(publishBlankAttempt(attempt.attemptId()).score()).isEqualTo(15);
        assertThat(reviewed.questions()).filteredOn(item -> item.id().equals(fill.getId())).singleElement()
                .satisfies(item -> assertThat(item.awardedPoints()).isEqualTo(7));
    }

    @Test void legacyPendingFillBlankPaperIsCompletedWithoutChangingPublishedScores() {
        var q = fillQuestion(); var candidate = user(); var attempt = service.start(publishFill(q, 10), candidate.getEmployeeNo());
        service.saveAnswers(attempt.attemptId(), candidate.getEmployeeNo(), answers(q, "人身；；设备"));
        tx.executeWithoutResult(s -> { var old = attempts.findById(attempt.attemptId()).orElseThrow();
            old.setStatus(AttemptStatus.SUBMITTED); old.setSubmittedAt(Instant.now()); });
        var grades = service.grades(candidate.getEmployeeNo());
        assertThat(grades).singleElement().satisfies(g -> { assertThat(g.status()).isEqualTo(AttemptStatus.SUBMITTED); assertThat(g.score()).isNull(); });
        assertThat(publishBlankAttempt(attempt.attemptId()).score()).isEqualTo(7);
        tx.executeWithoutResult(s -> { var old = attempts.findById(attempt.attemptId()).orElseThrow();
            old.setScore(9); old.getAnswers().get(0).setAwardedPoints(9); old.getAnswers().get(0).setGradingMethod(GradingMethod.MANUAL); });
        assertThat(service.grades(candidate.getEmployeeNo()).get(0).score()).isEqualTo(9);
    }

    private Question fillQuestion() {
        var q = question(QuestionType.FILL_BLANK);
        q.setStem("保证（ ）、（ ）和（ ）安全。"); q.setAnswer("人身；电网；设备"); q.setOptionsJson(null);
        return questions.save(q);
    }

    @Test void separateBlankInputsSaveResumeAndGradeWithoutLeakingReferenceAnswers() throws Exception {
        var q = fillQuestion(); var candidate = user();
        var started = service.start(publishFill(q, 10), candidate.getEmployeeNo());
        String saved = "[\"人身\",\"\",\"设备\"]";
        assertThat(started.questions().get(0).blankCount()).isEqualTo(3);
        assertThat(QuestionDtos.CandidateView.from(q).blankCount()).isEqualTo(3);
        service.saveAnswers(started.attemptId(), candidate.getEmployeeNo(), answers(q, saved));
        mvc.perform(get("/api/candidate/attempts/" + started.attemptId()).with(jwt()
                        .jwt(j -> j.subject(candidate.getEmployeeNo())).authorities(new SimpleGrantedAuthority("CANDIDATE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].blankCount").value(3))
                .andExpect(jsonPath("$.questions[0].savedAnswer").value(saved))
                .andExpect(jsonPath("$.questions[0].answer").doesNotExist())
                .andExpect(jsonPath("$.questions[0].correctAnswer").doesNotExist());
        assertThat(service.submit(started.attemptId(), candidate.getEmployeeNo(), answers(q, saved)).score()).isNull();
        assertThat(publishBlankAttempt(started.attemptId()).score()).isEqualTo(7);
        assertThat(service.review(started.attemptId(), candidate.getEmployeeNo()).questions().get(0).savedAnswer()).isEqualTo(saved);
    }

    @Test void personalizedWrongQuestionPaperAlsoAutoGradesFillBlanks() {
        var q = fillQuestion(); var candidate = user();
        var first = service.start(publishFill(q, 10), candidate.getEmployeeNo());
        service.submit(first.attemptId(), candidate.getEmployeeNo(), answers(q, "人身；；设备"));
        publishBlankAttempt(first.attemptId());
        var request = new ExamDtos.SaveRequest("填空错题复训", "TEST", List.of(), "", 30, 12, 12,
                null, null, false, 1, false, false, true, 1, List.of());
        Long examId = service.create(request).id(); service.publish(examId);
        var retake = service.start(examId, candidate.getEmployeeNo());
        var grade = service.submit(retake.attemptId(), candidate.getEmployeeNo(), answers(q, "人身；；设备"));
        assertThat(grade.status()).isEqualTo(AttemptStatus.SUBMITTED);
        grade = publishBlankAttempt(retake.attemptId());
        assertThat(grade.score()).isEqualTo(8);
        assertThat(grade.pendingSubjectiveCount()).isZero();
        assertThat(service.review(retake.attemptId(), candidate.getEmployeeNo()).questions()).singleElement()
                .satisfies(item -> { assertThat(item.objective()).isTrue(); assertThat(item.points()).isEqualTo(12); });
    }

    @Test void audiencesRestrictListingAndDirectStartAndFreezeProfessionalMembership() {
        var one=user();one.setProfessionalDirection("汽机-"+one.getId());users.save(one);
        var other=user();var q=question(QuestionType.SINGLE_CHOICE);
        var request=new ExamDtos.SaveRequest("定向考试","TEST",List.of(),"",30,100,90,null,null,false,1,false,true,false,20,
                List.of(new ExamDtos.QuestionItem(q.getId(),100,1)),false,"USERS",List.of(one.getId()),List.of());
        Long id=service.create(request).id();service.publish(id);
        assertThat(service.candidateList(one.getEmployeeNo())).extracting(ExamDtos.Summary::id).contains(id);
        assertThat(service.candidateList(other.getEmployeeNo())).extracting(ExamDtos.Summary::id).doesNotContain(id);
        assertThatThrownBy(()->service.start(id,other.getEmployeeNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var major=new ExamDtos.SaveRequest("专业考试","TEST",List.of(),"",30,100,90,null,null,false,1,false,true,false,20,
                List.of(new ExamDtos.QuestionItem(q.getId(),100,1)),false,"MAJORS",List.of(),List.of(one.getProfessionalDirection()));
        Long majorId=service.create(major).id();service.publish(majorId);
        other.setProfessionalDirection(one.getProfessionalDirection());users.save(other);
        assertThatThrownBy(()->service.start(majorId,other.getEmployeeNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(service.start(majorId,one.getEmployeeNo()).questions()).hasSize(1);
    }

    @Test void allCourseExamWrongsIgnoreChapterAndLimitPreserveHistoryAndExcludeOtherLearners() {
        var candidate=user();var q1=question(QuestionType.SINGLE_CHOICE);var q2=question(QuestionType.SINGLE_CHOICE);
        q1.setChapter("一");q2.setChapter("二");questions.saveAll(List.of(q1,q2));
        var first=service.start(publish(q1,false,1),candidate.getEmployeeNo());service.submit(first.attemptId(),candidate.getEmployeeNo(),answers(q1,"B"));
        var second=service.start(publish(q2,false,1),candidate.getEmployeeNo());service.submit(second.attemptId(),candidate.getEmployeeNo(),answers(q2,"B"));
        var insufficient=new ExamDtos.SaveRequest("总分不足","TEST",List.of(),"",30,1,1,null,null,false,1,false,true,true,1,List.of(),true,"USERS",List.of(candidate.getId()),List.of());
        Long insufficientId=service.create(insufficient).id();
        assertThatThrownBy(()->service.publish(insufficientId)).hasMessageContaining("总分");
        assertThat(exams.findById(insufficientId).orElseThrow().getStatus()).isEqualTo(ExamStatus.DRAFT);
        var req=new ExamDtos.SaveRequest("全部考试错题","TEST",List.of("一"),"",30,100,90,null,null,false,1,false,true,true,1,List.of(),true,"USERS",List.of(candidate.getId()),List.of());
        Long id=service.create(req).id();service.publish(id);var retry=service.start(id,candidate.getEmployeeNo());
        assertThat(retry.questions()).extracting(ExamDtos.AttemptQuestion::id).containsExactlyInAnyOrder(q1.getId(),q2.getId());
        service.submit(retry.attemptId(),candidate.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(q1.getId(),"A",q2.getId(),"B"),0));
        assertThat(wrong.unresolved(candidate.getId(),"EXAM")).extracting(Question::getId).containsExactly(q2.getId());
        assertThat(attempts.findById(first.attemptId()).orElseThrow().getScore()).isZero();
        assertThatThrownBy(()->service.start(id,user().getEmployeeNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void adminAloneCanGrantRolesAndDelegatesCannotTakeOverAdmin() throws Exception {
        var admin=users.findByEmployeeNo("admin").orElseThrow();admin.setMustChangePassword(false);users.save(admin);
        var a=user();var b=user();
        String body="{\"roles\":[\"SYSTEM_ADMIN\"]}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/"+a.getId()+"/roles")
            .with(jwt().jwt(j->j.subject("admin")).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"))).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/"+b.getId()+"/roles")
            .with(jwt().jwt(j->j.subject(a.getEmployeeNo())).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"))).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/roles")
            .with(jwt().jwt(j->j.subject(a.getEmployeeNo())).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"))).contentType("application/json").content("{\"code\":\"NEW_ROLE\",\"name\":\"测试\",\"permissionGroup\":\"TRAINING_ADMIN\"}")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/"+admin.getId()+"/reset-password")
            .with(jwt().jwt(j->j.subject(a.getEmployeeNo())).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"))).contentType("application/json").content("{\"password\":\"TestPassword123\"}")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/"+admin.getId())
            .with(jwt().jwt(j->j.subject(a.getEmployeeNo())).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"))).contentType("application/json").content("{\"employeeNo\":\"renamed-admin\",\"name\":\"测试\",\"status\":\"ACTIVE\"}")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/"+admin.getId())
            .with(jwt().jwt(j->j.subject("admin")).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"))).contentType("application/json").content("{\"employeeNo\":\"renamed-admin\",\"name\":\"测试\",\"status\":\"ACTIVE\"}")).andExpect(status().isBadRequest());
        assertThat(users.findById(b.getId()).orElseThrow().getRoles()).extracting(Role::getCode).containsExactly("CANDIDATE");
    }

    @Autowired com.huaneng.shanyin.hnexam.controller.DashboardController dashboard;
    @Test @WithMockUser(authorities="SYSTEM_ADMIN") void trendUsesLatestGradedAttemptPerLearnerAndExcludesCancelledExams() {
        var u=user();var q=question(QuestionType.SINGLE_CHOICE);Long id=publish(q,true,2);
        var first=service.start(id,u.getEmployeeNo());service.submit(first.attemptId(),u.getEmployeeNo(),answers(q,"B"));
        // Preserve aggregation compatibility with historical same-exam retakes, which new starts now prohibit.
        tx.executeWithoutResult(status -> { var old = new ExamAttempt(); old.setExam(exams.findById(id).orElseThrow()); old.setUser(u);
            old.setStartedAt(Instant.now()); old.setSubmittedAt(Instant.now()); old.setStatus(AttemptStatus.GRADED); old.setScore(100); old.setPassed(true); attempts.save(old); });
        var point=dashboard.trends().stream().filter(t->t.id().equals(id)).findFirst().orElseThrow();
        assertThat(point.participants()).isEqualTo(1);assertThat(point.graded()).isEqualTo(1);
        assertThat(point.averagePercent()).isEqualTo(100);assertThat(point.passRate()).isEqualTo(100);
        service.cancel(id,"取消","admin");assertThat(dashboard.trends()).extracting(com.huaneng.shanyin.hnexam.controller.DashboardController.ExamTrend::id).doesNotContain(id);
    }

    @Autowired com.huaneng.shanyin.hnexam.service.ProfileExcelService profileExcel;
    @Test void profileImportUpsertsByEmployeeAndPreservesCredentialsAndRoles() throws Exception {
        var existing=user();existing.setDepartment("保留部门");users.save(existing);String fresh="excel"+UUID.randomUUID().toString().substring(0,9);
        byte[] bytes;
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(profileExcel.template()));var out=new java.io.ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);
            String[][] rows={{fresh,"新学员","","","","电气","","","2026-10-08"},{existing.getEmployeeNo(),"更新姓名","","","","汽机","","",""},{"admin","禁止覆盖","","","","","","",""},{"bad-date","日期错误","","","","","","","2026-99-99"}};
            for(int i=0;i<rows.length;i++){var row=sheet.createRow(i+1);for(int c=0;c<rows[i].length;c++)row.createCell(c).setCellValue(rows[i][c]);}
            book.write(out);bytes=out.toByteArray();
        }
        var file=new org.springframework.mock.web.MockMultipartFile("file","档案.xlsx","application/octet-stream",bytes);
        var result=profileExcel.importWorkbook(file);assertThat(result.created()).isEqualTo(1);assertThat(result.updated()).isEqualTo(1);assertThat(result.failed()).isEqualTo(2);
        assertThat(result.credentials()).singleElement().satisfies(receipt->{
            assertThat(receipt.employeeNo()).isEqualTo(fresh);
            assertThat(receipt.initialPassword()).hasSize(25).isNotEqualTo("Candidate@12345");
            assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(receipt.initialPassword(),users.findByEmployeeNo(fresh).orElseThrow().getPasswordHash())).isTrue();
            assertThat(receipt.toString()).doesNotContain(receipt.initialPassword());
        });
        String originalHash=users.findByEmployeeNo(fresh).orElseThrow().getPasswordHash();
        var saved=users.findById(existing.getId()).orElseThrow();assertThat(saved.getName()).isEqualTo("更新姓名");assertThat(saved.getDepartment()).isEqualTo("保留部门");assertThat(saved.getPasswordHash()).isEqualTo(existing.getPasswordHash());
        assertThat(saved.getRoles()).extracting(Role::getCode).containsExactly("CANDIDATE");assertThat(users.findByEmployeeNo("bad-date")).isEmpty();
        var repeated=profileExcel.importWorkbook(file);
        assertThat(repeated.created()).isZero();assertThat(repeated.credentials()).isEmpty();
        assertThat(users.findByEmployeeNo(fresh).orElseThrow().getPasswordHash()).isEqualTo(originalHash);
    }

    @Test void makeupAudienceRefreshesAndExcludesPendingPassedAbsentAndDisabledPeople() {
        var failed=user();var laterPassed=user();var passed=user();var pending=user();var disabled=user();var absent=user();
        var q=question(QuestionType.CALCULATION); Long source=publish(q,false,1);
        Long revised=null;
        for (var u:List.of(failed,laterPassed,passed,pending,disabled)) {
            var a=service.start(source,u.getEmployeeNo()); service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"步骤"));
            if(u==pending)continue;
            service.manualGrade(a.attemptId(),"admin",new ExamDtos.ManualGradeRequest(Map.of(q.getId(),new ExamDtos.ManualScore(u==passed?100:40,"评分")),null));
            if(u==laterPassed)revised=a.attemptId();
        }
        disabled.setStatus(UserStatus.DISABLED);users.save(disabled);
        Long makeup=service.createMakeup(source).id();
        assertThat(service.detail(makeup).audienceUserIds()).containsExactlyInAnyOrder(failed.getId(),laterPassed.getId());
        service.manualGrade(revised,"admin",new ExamDtos.ManualGradeRequest(Map.of(q.getId(),new ExamDtos.ManualScore(100,"复核通过")),null));
        service.publish(makeup);
        assertThat(service.detail(makeup).audienceUserIds()).containsExactly(failed.getId());
        assertThatThrownBy(()->service.start(makeup,absent.getEmployeeNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test @WithMockUser(authorities="CANDIDATE") void dailyStreakResetsAndReentryCountsANewIncident() {
        var u=user(); var q=question(QuestionType.SINGLE_CHOICE);
        var token=Jwt.withTokenValue("test").header("alg","HS256").subject(u.getEmployeeNo()).build();
        for (String answer : List.of("B","A","B","A")) practice.submit(q.getId(),new QuestionDtos.PracticeSubmit(answer),token);
        assertThat(wrong.summary(u.getId(),"DAILY").unresolved()).isEqualTo(1);
        practice.submit(q.getId(),new QuestionDtos.PracticeSubmit("A"),token);
        assertThat(wrong.summary(u.getId(),"DAILY").resolutionRate()).isEqualTo(100);
        practice.submit(q.getId(),new QuestionDtos.PracticeSubmit("B"),token);
        assertThat(wrong.summary(u.getId(),"DAILY").entered()).isEqualTo(2);
        assertThat(wrong.summary(u.getId(),"DAILY").resolutionRate()).isEqualTo(50);
        assertThat(wrong.summary(u.getId(),"EXAM").entered()).isZero();
    }

    @Test void pendingBlankCannotBeOverriddenByOldAiEndpointAndRoundingDoesNotMeanCorrect() {
        var q=fillQuestion(); var u=user(); var a=service.start(publishFill(q,1),u.getEmployeeNo());
        var grade=service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"人身；供电网络；设备"));
        assertThat(grade.status()).isEqualTo(AttemptStatus.SUBMITTED); assertThat(grade.pendingSubjectiveCount()).isEqualTo(1);
        assertThat(wrong.summary(u.getId(),"EXAM").entered()).isZero();
        service.resolveFillBlank(a.attemptId(),q.getId(),new com.huaneng.shanyin.hnexam.service.FillBlankGrading.Result(3,3,1,true,false),"fixture");
        assertThat(service.review(a.attemptId(),null).questions().get(0).awardedPoints()).isNull();
        var g=blankReview.workspace(a.examId()).groups().get(0);
        blankReview.decide(a.examId(),new com.huaneng.shanyin.hnexam.service.FillBlankReviewService.DecisionRequest(g.questionId(),g.version(),g.slot(),g.submitted(),false,false,g.answerIds()),"admin");
        assertThat(publishBlankAttempt(a.attemptId()).score()).isEqualTo(1);
        assertThat(service.review(a.attemptId(),null).questions().get(0).correct()).isFalse();
        assertThat(wrong.summary(u.getId(),"EXAM").entered()).isEqualTo(1);
    }

    private ExamDtos.GradeView publishBlankAttempt(Long attemptId) {
        Long eid=service.review(attemptId,null).grade().examId();
        blankReview.publish(eid,"admin",blankReview.workspace(eid).readyCount());
        return service.review(attemptId,null).grade();
    }

    @Test @WithMockUser(authorities="SYSTEM_ADMIN") void deletionRemovesWholeCourseButPreservesHistoricalReview() throws Exception {
        var q=question(QuestionType.SINGLE_CHOICE); q.setCourseCode("删除验收-"+q.getId()); questions.save(q);
        var request=new ExamDtos.SaveRequest("删除历史保护",q.getCourseCode(),List.of(),"",30,100,90,null,null,false,1,false,false,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),100,1)));
        var u=user(); Long id=service.create(request).id(); service.publish(id); var a=service.start(id,u.getEmployeeNo());
        service.submit(a.attemptId(),u.getEmployeeNo(),answers(q,"A"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/questions/course")
                .contentType("application/json").content("{\"courseCode\":\""+q.getCourseCode()+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1));
        assertThat(questions.findById(q.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(service.review(a.attemptId(),u.getEmployeeNo()).grade().score()).isEqualTo(100);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/questions/"+q.getId()+"/status?status=ACTIVE"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/questions")).andExpect(jsonPath("$[?(@.id == "+q.getId()+")]").isEmpty());
    }

    @Autowired PracticeDayRepository practiceDays;
    @Test @WithMockUser(authorities="SYSTEM_ADMIN") void monthlyRanksAllUsersAndExcludesDailyFromExamResolutionRate() throws Exception {
        var fast=user();var slow=user();
        for (var u:List.of(fast,slow)) { var d=new PracticeDay(); d.setUser(u);d.setDate(java.time.LocalDate.of(2031,1,5));d.setSeconds(u==fast?500:100);practiceDays.save(d); }
        var ranking=dashboard.monthly("2031-01"); assertThat(ranking.get(0).id()).isEqualTo(fast.getId());assertThat(ranking.get(1).id()).isEqualTo(slow.getId());
        assertThat(ranking).hasSize((int)users.count()); assertThat(ranking.get(0).practiceSeconds()).isEqualTo(500);
        mvc.perform(get("/api/dashboard/monthly-learning?month=2031-01").with(jwt().authorities(new SimpleGrantedAuthority("CANDIDATE")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/monthly-learning?month=wrong").with(jwt().authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN")))).andExpect(status().isBadRequest());
    }

    private Long publishFill(Question q, int points) {
        var request = new ExamDtos.SaveRequest("填空自动判卷", "TEST", List.of(), "", 30, points, points,
                null, null, false, 1, false, false, false, 10, List.of(new ExamDtos.QuestionItem(q.getId(), points, 1)));
        Long id = service.create(request).id(); service.publish(id); return id;
    }

    private AppUser user() {
        AppUser u = new AppUser(); u.setEmployeeNo("t" + UUID.randomUUID().toString().substring(0, 12));
        u.setName("考试集成测试"); u.setPasswordHash("unused"); u.setMustChangePassword(false);
        u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());
        return users.save(u);
    }
    private Question question(QuestionType type) {
        Question q = new Question(); q.setType(type); q.setStem("测试题"); q.setAnswer("A");
        q.setOptionsJson("[\"A\",\"B\"]"); q.setExplanation("测试解析"); q.setCourseCode("TEST"); q.setStatus(QuestionStatus.ACTIVE);
        return questions.save(q);
    }
    private ExamDtos.SaveRequest request(Question q, boolean retake, int count) {
        return new ExamDtos.SaveRequest("考试测试", "TEST", List.of(), "自动化测试", 90, 100, 90, null, null, retake, count, true, true, false, 20,
                List.of(new ExamDtos.QuestionItem(q.getId(), 100, 1)));
    }
    private Long publish(Question q, boolean retake, int count) {
        Long id = service.create(request(q, retake, count)).id(); service.publish(id); return id;
    }
    private ExamDtos.SubmitRequest answers(Question q, String answer) {
        return new ExamDtos.SubmitRequest(Map.of(q.getId(), answer), 0);
    }
}
