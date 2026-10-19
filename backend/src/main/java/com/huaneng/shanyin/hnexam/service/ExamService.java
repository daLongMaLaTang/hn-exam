package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExamService {
    private final com.huaneng.shanyin.hnexam.service.PrivateAdministratorPolicy privateAdmins;
    private final ExamRepository examRepository;
    private final ExamAttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final UserRepository userRepository;
    private final PersonalExamPaperRepository personalPaperRepository;
    private final AnswerGrader answerGrader;
    private final FillBlankReviewService blankReview;
    private final WrongQuestionService wrongQuestions;
    @PersistenceContext private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<ExamDtos.Summary> list(boolean candidate) {
        return (candidate ? examRepository.findByStatusOrderByStartTimeDesc(ExamStatus.PUBLISHED)
                : examRepository.findAllByOrderByIdDesc()).stream().map(ExamDtos.Summary::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ExamDtos.Summary> candidateList(String employeeNo) {
        AppUser user = findUser(employeeNo);
        return examRepository.findAllByOrderByIdDesc().stream().filter(e -> e.getStatus() == ExamStatus.PUBLISHED || e.getStatus() == ExamStatus.CANCELLED)
                .filter(e -> audienceAllows(e, user))
                .filter(e -> !e.isWrongQuestionPaper() || personalPaperRepository.existsByExamIdAndUserId(e.getId(), user.getId()))
                .map(ExamDtos.Summary::from).toList();
    }

    @Transactional(readOnly = true)
    public ExamDtos.Detail detail(Long id) { return ExamDtos.Detail.from(findExam(id)); }

    @Transactional
    public ExamDtos.Summary create(ExamDtos.SaveRequest request) {
        validate(request);
        Exam exam = new Exam();
        apply(exam, request);
        return ExamDtos.Summary.from(examRepository.save(exam));
    }

    @Transactional
    public ExamDtos.Summary createMakeup(Long sourceId) {
        Exam source = lockExam(sourceId);
        Set<Long> failed = failedCandidates(source);
        if (failed.isEmpty()) throw new IllegalStateException("原考试暂无已判卷且未及格的启用学员");
        Exam exam = new Exam();
        exam.setTitle(source.getTitle().substring(0, Math.min(150, source.getTitle().length())) + "（补考）");
        exam.setCourseCode(source.getCourseCode()); exam.setDescription("补考来源：" + source.getTitle());
        exam.setMakeupSourceExamId(sourceId); exam.setAudienceMode("USERS"); exam.getAudienceUserIds().addAll(failed);
        exam.setChaptersJson(source.getChaptersJson()); exam.setDurationMinutes(source.getDurationMinutes());
        exam.setTotalScore(source.getTotalScore()); exam.setPassScore(source.getPassScore());
        exam.setShuffleQuestions(source.isShuffleQuestions()); exam.setBasicAntiCheat(source.isBasicAntiCheat());
        exam.setWrongQuestionPaper(source.isWrongQuestionPaper()); exam.setWrongQuestionLimit(source.getWrongQuestionLimit());
        exam.setAllWrongQuestions(source.isAllWrongQuestions());
        for (ExamQuestion original : source.getQuestions()) {
            ExamQuestion item = new ExamQuestion(); item.setExam(exam); item.setQuestion(original.getQuestion());
            item.setPoints(original.getPoints()); item.setPosition(original.getPosition()); exam.getQuestions().add(item);
        }
        return ExamDtos.Summary.from(examRepository.save(exam));
    }

    private Set<Long> failedCandidates(Exam source) {
        if (source.getStatus() != ExamStatus.PUBLISHED && source.getStatus() != ExamStatus.CLOSED)
            throw new IllegalStateException("只能从已发布或已关闭的考试创建补考");
        Map<Long, ExamAttempt> latest = new LinkedHashMap<>();
        attemptRepository.findByExamIdOrderByStartedAtDesc(source.getId()).forEach(a -> latest.putIfAbsent(a.getUser().getId(), a));
        return latest.values().stream().filter(a -> a.getStatus() == AttemptStatus.GRADED && Boolean.FALSE.equals(a.getPassed()))
                .filter(a -> a.getUser().getStatus() == UserStatus.ACTIVE && a.getUser().getRoles().stream().anyMatch(r -> r.effectiveAuthority().equals("CANDIDATE")))
                .map(a -> a.getUser().getId()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional
    public ExamDtos.Summary update(Long id, ExamDtos.SaveRequest request) {
        validate(request);
        Exam exam = lockExam(id);
        if (exam.getStatus() != ExamStatus.DRAFT) throw new IllegalStateException("只有草稿考试可以编辑");
        exam.getQuestions().clear();
        // Flush orphan removals before reinserting an existing question under its unique key.
        examRepository.flush();
        apply(exam, request);
        return ExamDtos.Summary.from(exam);
    }

    @Transactional
    public ExamDtos.Summary publish(Long id) {
        Exam exam = lockExam(id);
        if (exam.getStatus() != ExamStatus.DRAFT) throw new IllegalStateException("只能发布草稿考试");
        if (!exam.isWrongQuestionPaper() && exam.getQuestions().isEmpty()) throw new IllegalStateException("试卷至少需要一道题");
        if (exam.getEndTime() != null && !Instant.now().isBefore(exam.getEndTime())) throw new IllegalStateException("考试结束时间已过，请先修改时间");
        freezeAudience(exam);
        if (exam.isWrongQuestionPaper()) generatePersonalPapers(exam);
        else for (ExamQuestion item : exam.getQuestions()) { ensureEligible(item.getQuestion()); item.captureSnapshot(); }
        exam.setStatus(ExamStatus.PUBLISHED);
        return ExamDtos.Summary.from(exam);
    }

    @Transactional
    public ExamDtos.Summary close(Long id) {
        Exam exam = lockExam(id);
        if (exam.getStatus() != ExamStatus.PUBLISHED) throw new IllegalStateException("只能关闭已发布考试");
        exam.setStatus(ExamStatus.CLOSED);
        attemptRepository.findByExamIdOrderByStartedAtDesc(id).stream().sorted(Comparator.comparing(ExamAttempt::getId)).forEach(a -> {
            ExamAttempt locked = lockAttempt(a.getId());
            if (locked.getStatus() == AttemptStatus.IN_PROGRESS) grade(locked, Instant.now());
        });
        return ExamDtos.Summary.from(exam);
    }

    @Transactional
    public ExamDtos.Summary cancel(Long id, String reason, String actor) {
        Exam exam = lockExam(id);
        if (exam.getStatus() != ExamStatus.PUBLISHED) throw new IllegalStateException("只能取消已发布考试");
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("请填写取消原因（500字以内）");
        exam.setStatus(ExamStatus.CANCELLED); exam.setCancellationReason(reason.trim()); exam.setCancelledAt(Instant.now()); exam.setCancelledBy(privateAdmins.actorLabel(actor));
        attemptRepository.findByExamIdOrderByStartedAtDesc(id).stream().sorted(Comparator.comparing(ExamAttempt::getId)).forEach(a -> { ExamAttempt locked = lockAttempt(a.getId()); if (locked.getStatus() == AttemptStatus.IN_PROGRESS) { locked.setStatus(AttemptStatus.CANCELLED); locked.setSubmittedAt(Instant.now()); } });
        return ExamDtos.Summary.from(exam);
    }

    @Transactional
    public ExamDtos.AttemptView start(Long examId, String employeeNo) {
        Exam exam = findExam(examId);
        // Lock only the current user. Distinct candidates can start together while duplicate starts
        // from the same account remain serialized.
        AppUser user = lockUser(employeeNo);
        entityManager.refresh(exam, LockModeType.PESSIMISTIC_READ);
        if (!audienceAllows(exam, user)) throw new org.springframework.security.access.AccessDeniedException("您不在本场考试的发布范围内");
        if (exam.getStatus() == ExamStatus.CANCELLED) throw new IllegalStateException("考试已取消");
        List<ExamAttempt> existing = attemptRepository.findByExamIdAndUserIdOrderByStartedAtDesc(examId, user.getId());
        for (ExamAttempt item : existing.stream().sorted(Comparator.comparing(ExamAttempt::getId)).toList()) {
            if (item.getStatus() == AttemptStatus.SUBMITTED) finishPendingFillBlanks(lockAttempt(item.getId()));
            if (item.getStatus() == AttemptStatus.IN_PROGRESS) {
                ExamAttempt active = lockAttempt(item.getId());
                expireIfDue(active);
                if (active.getStatus() == AttemptStatus.IN_PROGRESS) return toAttemptView(active);
            }
        }
        if (existing.stream().anyMatch(a -> a.getStatus() == AttemptStatus.SUBMITTED))
            throw new IllegalStateException("本场答卷正在判卷，请等待成绩公布；补考由管理员另行安排");
        ensureAvailable(exam);
        PersonalExamPaper personalPaper = null;
        if (exam.isWrongQuestionPaper()) personalPaper = personalPaperRepository.findByExamIdAndUserId(examId, user.getId())
                .orElseThrow(() -> new IllegalStateException("本次考试没有可供您复训的考试错题"));
        if (existing.stream().anyMatch(a -> Boolean.TRUE.equals(a.getPassed()))) throw new IllegalStateException("已通过本场考试，无需补考");
        if (!existing.isEmpty()) throw new IllegalStateException("每场考试只能参加一次，补考请等待管理员新建考试");
        ensureSnapshots(exam);
        ExamAttempt attempt = new ExamAttempt();
        attempt.setExam(exam);
        attempt.setUser(user);
        attempt.setPersonalPaper(personalPaper);
        attempt.setStartedAt(Instant.now());
        attempt.setDeadline(deadline(attempt));
        attemptRepository.saveAndFlush(attempt);
        return toAttemptView(attempt);
    }

    @Transactional
    public ExamDtos.AttemptView getAttempt(Long attemptId, String employeeNo) {
        ExamAttempt attempt = ownedAttempt(attemptId, employeeNo);
        expireIfDue(attempt);
        return toAttemptView(attempt);
    }

    @Transactional
    public ExamDtos.SaveAck saveAnswers(Long attemptId, String employeeNo, ExamDtos.SubmitRequest request) {
        ExamAttempt attempt = ownedAttempt(attemptId, employeeNo);
        expireIfDue(attempt);
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) {
            applyAnswers(attempt, request);
            if (attempt.getExam().isBasicAntiCheat() && attempt.getFocusLossCount() >= 2) grade(attempt, Instant.now());
        }
        return new ExamDtos.SaveAck(attempt.getStatus(), Instant.now(), attempt.getFocusLossCount());
    }

    @Transactional
    public ExamDtos.GradeView submit(Long attemptId, String employeeNo, ExamDtos.SubmitRequest request) {
        ExamAttempt attempt = ownedAttempt(attemptId, employeeNo);
        expireIfDue(attempt);
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) {
            applyAnswers(attempt, request);
            grade(attempt, Instant.now());
        }
        return ExamDtos.GradeView.from(attempt);
    }

    @Transactional
    public List<ExamDtos.GradeView> grades(String employeeNo) {
        Long userId = findUser(employeeNo).getId();
        List<ExamAttempt> attempts = attemptRepository.findByUserIdOrderByStartedAtDesc(userId);
        attempts.stream().sorted(Comparator.comparing(ExamAttempt::getId)).forEach(a -> { if (a.getStatus() == AttemptStatus.IN_PROGRESS || a.getStatus() == AttemptStatus.SUBMITTED) expireIfDue(lockAttempt(a.getId())); });
        return attempts.stream().map(ExamDtos.GradeView::from).toList();
    }

    @Transactional
    public List<ExamDtos.GradeView> results(Long examId) {
        findExam(examId);
        List<ExamAttempt> attempts = attemptRepository.findByExamIdOrderByStartedAtDesc(examId);
        attempts.stream().sorted(Comparator.comparing(ExamAttempt::getId)).forEach(a -> { if (a.getStatus() == AttemptStatus.IN_PROGRESS || a.getStatus() == AttemptStatus.SUBMITTED) expireIfDue(lockAttempt(a.getId())); });
        return attempts.stream().map(ExamDtos.GradeView::from).toList();
    }

    @Transactional
    public ExamDtos.Statistics statistics(Long examId) {
        List<ExamDtos.GradeView> all = results(examId);
        List<ExamDtos.GradeView> graded = findExam(examId).getStatus() == ExamStatus.CANCELLED ? List.of() : all.stream().filter(a -> a.status() == AttemptStatus.GRADED).toList();
        IntSummaryStatistics scores = graded.stream().mapToInt(ExamDtos.GradeView::score).summaryStatistics();
        long passed = graded.stream().filter(ExamDtos.GradeView::passed).count();
        List<ExamDtos.ScoreBand> bands = List.of(
                new ExamDtos.ScoreBand("60以下", graded.stream().filter(g -> percent(g) < 60).count()),
                new ExamDtos.ScoreBand("60–<70", graded.stream().filter(g -> percent(g) >= 60 && percent(g) < 70).count()),
                new ExamDtos.ScoreBand("70–<80", graded.stream().filter(g -> percent(g) >= 70 && percent(g) < 80).count()),
                new ExamDtos.ScoreBand("80–<90", graded.stream().filter(g -> percent(g) >= 80 && percent(g) < 90).count()),
                new ExamDtos.ScoreBand("90–100", graded.stream().filter(g -> percent(g) >= 90).count()));
        Map<Long, long[]> counts = new LinkedHashMap<>();
        Map<Long, AttemptItem> statisticItems = new LinkedHashMap<>();
        attemptRepository.findByExamIdOrderByStartedAtDesc(examId).stream().filter(a -> a.getStatus() == AttemptStatus.GRADED && a.getExam().getStatus() != ExamStatus.CANCELLED).forEach(a -> {
            Map<Long, AttemptAnswer> saved = answerMap(a);
            attemptItems(a).forEach(item -> {
                statisticItems.putIfAbsent(item.question().getId(), item);
                long[] value = counts.computeIfAbsent(item.question().getId(), ignored -> new long[2]);
                value[0]++;
                if (!Boolean.TRUE.equals(saved.get(item.question().getId()) == null ? null : saved.get(item.question().getId()).getCorrect())) value[1]++;
            });
        });
        List<ExamDtos.QuestionStatistic> questionStats = counts.entrySet().stream().map(entry -> {
            AttemptItem item = statisticItems.get(entry.getKey()); long answered = entry.getValue()[0], wrong = entry.getValue()[1];
            return new ExamDtos.QuestionStatistic(item.question().getId(), item.stem(), item.type(), chapter(item.question()),
                    answered, wrong, answered == 0 ? 0 : wrong * 100.0 / answered);
        }).filter(q -> q.wrongCount() > 0).sorted(Comparator.comparingDouble(ExamDtos.QuestionStatistic::errorRate).reversed()
                .thenComparing(Comparator.comparingLong(ExamDtos.QuestionStatistic::wrongCount).reversed())).limit(10).toList();
        return new ExamDtos.Statistics(examId, graded.size(), all.stream().map(ExamDtos.GradeView::userId).distinct().count(),
                passed, graded.isEmpty() ? 0 : passed * 100.0 / graded.size(), scores.getAverage(),
                graded.isEmpty() ? 0 : scores.getMax(), graded.isEmpty() ? 0 : scores.getMin(), bands, questionStats);
    }

    @Transactional
    public ExamDtos.Review review(Long id, String employeeNo) {
        ExamAttempt attempt = employeeNo == null ? lockAttempt(id) : ownedAttempt(id, employeeNo);
        expireIfDue(attempt);
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) throw new IllegalStateException("交卷后才能查看答卷");
        if (employeeNo != null && attempt.getExam().getStatus() == ExamStatus.CANCELLED) throw new IllegalStateException("考试已取消，成绩不计入统计");
        if (employeeNo != null && attempt.getStatus() != AttemptStatus.GRADED) throw new IllegalStateException("主观题正在人工判卷，完成后可查看答案解析");
        return toReview(attempt);
    }

    @Transactional
    public ExamDtos.Review manualGrade(Long id, String graderEmployeeNo, ExamDtos.ManualGradeRequest request) {
        ExamAttempt attempt = lockAttempt(id);
        if (attempt.getStatus() == AttemptStatus.CANCELLED || attempt.getExam().getStatus() == ExamStatus.CANCELLED) throw new IllegalStateException("考试已取消，不能判卷");
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) throw new IllegalStateException("考生尚未交卷，不能判卷");
        ensureSnapshots(attempt.getExam());
        boolean filledPendingBlanks = finishPendingFillBlanks(attempt);
        List<AttemptItem> subjective = attemptItems(attempt).stream().filter(item -> needsManual(item, answerMap(attempt).get(item.question().getId()))).toList();
        if (subjective.isEmpty() && filledPendingBlanks) return toReview(attempt);
        if (subjective.isEmpty()) throw new IllegalStateException("本答卷没有需要人工判分的题目");
        Set<Long> requiredIds = subjective.stream().map(item -> item.question().getId()).collect(Collectors.toSet());
        if (request.scores() == null || !request.scores().keySet().equals(requiredIds)) throw new IllegalArgumentException("请完成全部主观题评分后再提交判卷");
        Map<Long, AttemptAnswer> answers = answerMap(attempt);
        for (AttemptItem item : subjective) {
            ExamDtos.ManualScore score = request.scores().get(item.question().getId());
            if (score == null || score.awardedPoints() == null || score.awardedPoints() < 0 || score.awardedPoints() > item.points()) throw new IllegalArgumentException("第 " + item.position() + " 题得分不能超过 " + item.points() + " 分且不能小于0");
            Double similarity = score.semanticSimilarity();
            if ((item.type() == QuestionType.SHORT_ANSWER && similarity == null) || (similarity != null && (!Double.isFinite(similarity) || similarity < 0 || similarity > 100))) throw new IllegalArgumentException("请填写简答题语义相似度（0至100%），相似度与得分分别评定");
            AttemptAnswer answer = answers.get(item.question().getId());
            answer.setAwardedPoints(score.awardedPoints());
            answer.setSemanticSimilarity(similarity);
            answer.setSimilaritySource(similarity == null ? null : "MANUAL_REVIEW");
            answer.setCorrect(item.type() == QuestionType.SHORT_ANSWER ? similarity >= 30 : score.awardedPoints() == item.points());
            if (item.type() == QuestionType.FILL_BLANK) {
                int blanks = FillBlankGrading.blankCount(item.answer());
                if (score.correctBlanks() == null || score.correctBlanks() < 0 || score.correctBlanks() > blanks)
                    throw new IllegalArgumentException("填空题请填写答对空数（0至" + blanks + "）");
                int points = (int)Math.round((double)item.points() * score.correctBlanks() / blanks);
                if (points != score.awardedPoints()) throw new IllegalArgumentException("填空题得分须按答对空数比例计算");
                answer.setCorrect(score.correctBlanks() == blanks); answer.setAiStatus("MANUAL_REVIEWED");
            }
            answer.setGradingMethod(GradingMethod.MANUAL);
            answer.setGradingComment(trimToNull(score.comment()));
            if(answer.getShortReviewState()!=null) {
                answer.setShortRunToken(null);answer.setShortReviewState("ACCEPTED");answer.setShortRevision(answer.getShortRevision()+1);
                answer.setShortSampleDisagreed(answer.isShortSampleDisagreed() || (answer.getAiSuggestedPoints()!=null && !answer.getAiSuggestedPoints().equals(score.awardedPoints())));
                answer.setShortSampleApproved(false);
            }
        }
        int total = attemptItems(attempt).stream().mapToInt(item -> Optional.ofNullable(answers.get(item.question().getId()).getAwardedPoints()).orElse(0)).sum();
        attempt.setScore(total);
        attempt.setPassed(total >= attempt.getExam().getPassScore());
        boolean groupedBlanks = ShortAnswerReviewService.centrallyGraded(attempt);
        attempt.setStatus(groupedBlanks ? AttemptStatus.SUBMITTED : AttemptStatus.GRADED);
        attempt.setGradedAt(groupedBlanks ? null : Instant.now());
        attempt.setGradedByEmployeeNo(groupedBlanks ? null : privateAdmins.actorLabel(graderEmployeeNo));
        if (groupedBlanks) { attempt.setScore(null); attempt.setPassed(null); }
        attempt.setGradingNote(trimToNull(request.overallComment()));
        GradingRevision revision = new GradingRevision(); revision.setAttemptId(id); revision.setActor(privateAdmins.actorLabel(graderEmployeeNo)); revision.setRecordedAt(Instant.now()); revision.setComment(request.overallComment());
        try { revision.setScoresJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.scores())); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("保存判卷记录失败"); }
        entityManager.persist(revision);
        return toReview(attempt);
    }

    private ExamDtos.Review toReview(ExamAttempt attempt) {
        ensureSnapshots(attempt.getExam());
        Map<Long, AttemptAnswer> answers = answerMap(attempt);
        List<ExamDtos.ReviewQuestion> questions = orderedItems(attempt).stream().map(item -> {
            AttemptAnswer a = answers.get(item.question().getId());
            return new ExamDtos.ReviewQuestion(item.question().getId(), item.type(), item.stem(),
                    item.optionsJson(), item.points(), item.position(), a == null ? null : a.getAnswerText(),
                    item.answer(), item.explanation(), a == null ? null : a.getCorrect(),
                    a == null ? null : a.getAwardedPoints(), !needsManual(item, a),
                    a == null ? null : a.getGradingMethod(), a == null ? null : a.getGradingComment(),
                    a == null ? null : a.getAiSuggestedPoints(), a == null ? null : a.getAiSuggestionReason(),
                    a == null ? null : a.getSemanticSimilarity(), a == null ? null : a.getSimilaritySource(),
                    a == null ? null : a.getAiSuggestedSimilarity(), a == null ? null : a.getAiModel(), a == null ? null : a.getAiStatus());
        }).toList();
        return new ExamDtos.Review(ExamDtos.GradeView.from(attempt), questions);
    }

    // Called through the Spring proxy by ExamExpiryScheduler: one transaction / lock per paper.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void expireAttempt(Long id) {
        ExamAttempt attempt = attemptRepository.findById(id).orElse(null);
        if (attempt == null || !isDue(attempt)) return;
        entityManager.refresh(attempt, LockModeType.PESSIMISTIC_WRITE,
                Map.of("jakarta.persistence.lock.timeout", 1000));
        // A browser submission or another scheduler may have completed this paper while we waited.
        expireIfDue(attempt);
    }

    private boolean isDue(ExamAttempt attempt) {
        return attempt.getStatus() == AttemptStatus.IN_PROGRESS
                && (!Instant.now().isBefore(deadline(attempt)) || attempt.getExam().getStatus() == ExamStatus.CLOSED);
    }

    private void expireIfDue(ExamAttempt attempt) {
        if (attempt.getStatus() == AttemptStatus.SUBMITTED) {
            finishPendingFillBlanks(attempt);
            return;
        }
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) return;
        Instant deadline = deadline(attempt);
        if (!Instant.now().isBefore(deadline) || attempt.getExam().getStatus() == ExamStatus.CLOSED) {
            grade(attempt, Instant.now().isBefore(deadline) ? Instant.now() : deadline);
        }
    }

    private void applyAnswers(ExamAttempt attempt, ExamDtos.SubmitRequest request) {
        if (request.answers() == null || request.focusLossCount() < 0) throw new IllegalArgumentException("答卷格式不正确");
        Map<Long, AttemptItem> items = attemptItems(attempt).stream()
                .collect(Collectors.toMap(q -> q.question().getId(), Function.identity()));
        if (!items.keySet().containsAll(request.answers().keySet())) throw new IllegalArgumentException("答案包含非本试卷题目");
        Map<Long, AttemptAnswer> saved = answerMap(attempt);
        request.answers().forEach((id, value) -> {
            if (value != null && value.length() > 10000) throw new IllegalArgumentException("答案内容过长");
            AttemptAnswer answer = saved.get(id);
            if (answer == null) {
                answer = new AttemptAnswer();
                answer.setAttempt(attempt);
                answer.setQuestion(items.get(id).question());
                attempt.getAnswers().add(answer);
            }
            answer.setAnswerText(value);
        });
        attempt.setFocusLossCount(Math.max(attempt.getFocusLossCount(), request.focusLossCount()));
    }

    private void grade(ExamAttempt attempt, Instant submittedAt) {
        ensureSnapshots(attempt.getExam());
        Map<Long, AttemptAnswer> saved = answerMap(attempt);
        int score = 0;
        boolean requiresManualGrading = false;
        for (AttemptItem item : attemptItems(attempt)) {
            AttemptAnswer answer = saved.get(item.question().getId());
            if (answer == null) {
                answer = new AttemptAnswer();
                answer.setAttempt(attempt);
                answer.setQuestion(item.question());
                attempt.getAnswers().add(answer);
            }
            if (answerGrader.isObjective(item.type())) {
                gradeObjective(item, answer);
                if (answer.getAwardedPoints() == null) requiresManualGrading = true;
                else score += answer.getAwardedPoints();
            } else {
                requiresManualGrading = true;
                answer.setCorrect(null);
                answer.setAwardedPoints(null);
                answer.setGradingMethod(null);
                answer.setGradingComment(null);
            }
        }
        attempt.setSubmittedAt(submittedAt);
        if (requiresManualGrading || attemptItems(attempt).stream().anyMatch(i -> i.type() == QuestionType.FILL_BLANK)) {
            attempt.setScore(null);
            attempt.setPassed(null);
            attempt.setStatus(AttemptStatus.SUBMITTED);
            attempt.setGradedAt(null);
            attempt.setGradedByEmployeeNo(null);
        } else {
            attempt.setScore(score);
            attempt.setPassed(score >= attempt.getExam().getPassScore());
            attempt.setStatus(AttemptStatus.GRADED);
            attempt.setGradedAt(submittedAt);
            attempt.setGradedByEmployeeNo("SYSTEM_AUTO");
        }
    }

    private void gradeObjective(AttemptItem item, AttemptAnswer answer) {
        if (item.type() == QuestionType.FILL_BLANK) {
            blankReview.initialize(new FillBlankReviewService.Item(item.question().getId(), item.stem(), item.answer(), item.points()), answer);
            return;
        }
        boolean correct = answerGrader.isCorrect(item.type(), item.answer(), answer.getAnswerText());
        answer.setCorrect(correct);
        answer.setAwardedPoints(correct ? item.points() : 0);
        answer.setGradingMethod(GradingMethod.AUTO);
        answer.setGradingComment(null);
    }

    // Compatibility for papers submitted before fill-in-the-blank automatic grading was enabled.
    // Call only with a locked attempt. Published results and manually awarded points are left intact.
    private boolean finishPendingFillBlanks(ExamAttempt attempt) {
        if (attempt.getStatus() != AttemptStatus.SUBMITTED || attempt.getExam().getStatus() == ExamStatus.CANCELLED) return false;
        ensureSnapshots(attempt.getExam());
        List<AttemptItem> items = attemptItems(attempt);
        Map<Long, AttemptAnswer> answers = answerMap(attempt);
        boolean changed = false;
        for (AttemptItem item : items) {
            if (item.type() != QuestionType.FILL_BLANK) continue;
            AttemptAnswer answer = answers.get(item.question().getId());
            if (answer != null && (!answer.getBlankMarks().isEmpty() || answer.getAwardedPoints() != null)) continue;
            if (answer == null) {
                answer = new AttemptAnswer();
                answer.setAttempt(attempt);
                answer.setQuestion(item.question());
                attempt.getAnswers().add(answer);
                answers.put(item.question().getId(), answer);
            }
            gradeObjective(item, answer);
            changed = true;
        }
        return changed;
    }

    private void gradeObjective(ExamQuestion item, AttemptAnswer answer) {
        gradeObjective(new AttemptItem(item.getQuestion(), item.getSnapshotType(), item.getSnapshotStem(),
                item.getSnapshotOptionsJson(), item.getSnapshotAnswer(), item.getSnapshotExplanation(),
                item.getPoints(), item.getPosition()), answer);
    }

    private boolean needsManual(AttemptItem item, AttemptAnswer answer) {
        if (item.type() == QuestionType.FILL_BLANK && answer != null && !answer.getBlankMarks().isEmpty()) return false;
        return !answerGrader.isObjective(item.type()) || (item.type() == QuestionType.FILL_BLANK && answer != null
                && (answer.getAwardedPoints() == null || answer.getGradingMethod() == GradingMethod.MANUAL));
    }

    // Kept for compatibility with older callers; exam blanks no longer accept AI decisions.
    @Transactional
    public void resolveFillBlank(Long attemptId, Long questionId, FillBlankGrading.Result result, String model) {
        ExamAttempt attempt = lockAttempt(attemptId);
        finishPendingFillBlanks(attempt);
    }

    private void validate(ExamDtos.SaveRequest r) {
        String mode = r.audienceMode() == null ? "ALL" : r.audienceMode();
        if (!Set.of("ALL", "USERS", "MAJORS").contains(mode)) throw new IllegalArgumentException("发布对象无效");
        if (mode.equals("USERS") && (r.audienceUserIds() == null || r.audienceUserIds().isEmpty())) throw new IllegalArgumentException("请至少选择一位学员");
        if (mode.equals("MAJORS") && (r.audienceMajors() == null || r.audienceMajors().isEmpty())) throw new IllegalArgumentException("请至少选择一个专业");
        if (r.title() == null || r.title().isBlank() || r.courseCode() == null || r.courseCode().isBlank() || r.questions() == null) throw new IllegalArgumentException("请填写考试名称和课程");
        if (!r.wrongQuestionPaper() && r.questions().isEmpty()) throw new IllegalArgumentException("普通试卷至少需要一道题");
        if (r.wrongQuestionPaper() && (r.wrongQuestionLimit() < 1 || r.wrongQuestionLimit() > 200)) throw new IllegalArgumentException("每人错题数量须为1至200道");
        if (r.questions().size() > 1000) throw new IllegalArgumentException("单份试卷最多1000题");
        if (r.durationMinutes() < 1 || r.durationMinutes() > 1440 || r.totalScore() < 1 || r.passScore() < 0 || r.maxAttempts() < 1 || r.maxAttempts() > 20) throw new IllegalArgumentException("考试参数不正确");
        if (r.questions().stream().anyMatch(q -> q.questionId() == null || q.points() < 1 || q.position() < 1)) throw new IllegalArgumentException("试题分值和序号必须大于0");
        if (r.questions().stream().map(ExamDtos.QuestionItem::questionId).distinct().count() != r.questions().size()) throw new IllegalArgumentException("试卷中不能重复添加同一道题");
        if (r.questions().stream().map(ExamDtos.QuestionItem::position).distinct().count() != r.questions().size()) throw new IllegalArgumentException("题目序号不能重复");
        long points = r.questions().stream().mapToLong(ExamDtos.QuestionItem::points).sum();
        if (!r.wrongQuestionPaper() && points != r.totalScore()) throw new IllegalArgumentException("各题分值合计必须等于试卷总分");
        if (r.passScore() > r.totalScore()) throw new IllegalArgumentException("及格分不能高于总分");
        if (r.startTime() != null && r.endTime() != null && !r.endTime().isAfter(r.startTime())) throw new IllegalArgumentException("结束时间必须晚于开始时间");
    }

    private void apply(Exam e, ExamDtos.SaveRequest r) {
        e.setTitle(r.title().trim()); e.setCourseCode(r.courseCode().trim()); e.setDescription(r.description()); e.setDurationMinutes(r.durationMinutes());
        e.setChaptersJson(normalizeChapters(r.chapters())); e.setWrongQuestionPaper(r.wrongQuestionPaper());
        e.setWrongQuestionLimit(r.wrongQuestionLimit() < 1 ? 20 : r.wrongQuestionLimit());
        e.setAllWrongQuestions(r.wrongQuestionPaper() && r.allWrongQuestions());
        if (e.isAllWrongQuestions()) e.setChaptersJson(null);
        e.setAudienceMode(r.audienceMode() == null ? "ALL" : r.audienceMode());
        e.getAudienceUserIds().clear(); e.getAudienceMajors().clear(); e.setAudienceFrozen(false);
        if (e.getAudienceMode().equals("USERS")) e.getAudienceUserIds().addAll(r.audienceUserIds());
        if (e.getAudienceMode().equals("MAJORS")) e.getAudienceMajors().addAll(r.audienceMajors().stream().map(String::trim).filter(s -> !s.isBlank()).toList());
        e.setTotalScore(r.totalScore()); e.setPassScore(r.passScore()); e.setStartTime(r.startTime()); e.setEndTime(r.endTime());
        e.setAllowRetake(false); e.setMaxAttempts(1);
        e.setShuffleQuestions(r.shuffleQuestions()); e.setBasicAntiCheat(r.basicAntiCheat());
        Set<String> scopedChapters = parseChapters(e.getChaptersJson());
        for (ExamDtos.QuestionItem requested : r.questions()) {
            Question q = questionRepository.findById(requested.questionId()).orElseThrow(() -> new IllegalArgumentException("试题不存在：" + requested.questionId()));
            ensureEligible(q);
            if (!r.courseCode().trim().equals(q.getCourseCode())) throw new IllegalArgumentException("试题 #" + q.getId() + " 不属于课程“" + r.courseCode().trim() + "”");
            if (!scopedChapters.isEmpty() && !scopedChapters.contains(chapter(q))) throw new IllegalArgumentException("试题 #" + q.getId() + " 不在所选章节范围内");
            ExamQuestion item = new ExamQuestion();
            item.setExam(e); item.setQuestion(q); item.setPoints(requested.points()); item.setPosition(requested.position());
            e.getQuestions().add(item);
        }
    }

    private void ensureEligible(Question q) {
        if (q.getType() == QuestionType.FILL_BLANK) FillBlankGrading.validateReference(q.getAnswer());
        if (q.getStatus() != QuestionStatus.ACTIVE) throw new IllegalArgumentException("只能选用已启用的题目：" + q.getId());
        if (q.getAnswer() == null || q.getAnswer().isBlank()) throw new IllegalArgumentException("试题未设置正确答案：" + q.getId());
    }

    private void ensureAvailable(Exam e) {
        Instant now = Instant.now();
        if (e.getStatus() != ExamStatus.PUBLISHED) throw new IllegalStateException("考试未发布或已关闭");
        if (e.getStartTime() != null && now.isBefore(e.getStartTime())) throw new IllegalStateException("考试尚未开始");
        if (e.getEndTime() != null && !now.isBefore(e.getEndTime())) throw new IllegalStateException("考试已经结束");
    }

    private Instant deadline(ExamAttempt a) {
        if (a.getDeadline() != null) return a.getDeadline();
        Instant durationEnd = a.getStartedAt().plus(a.getExam().getDurationMinutes(), ChronoUnit.MINUTES);
        Instant examEnd = a.getExam().getEndTime();
        return examEnd != null && examEnd.isBefore(durationEnd) ? examEnd : durationEnd;
    }

    private void ensureSnapshots(Exam exam) {
        // Compatibility for exams created before snapshot columns were introduced.
        exam.getQuestions().stream().filter(q -> q.getSnapshotType() == null).forEach(ExamQuestion::captureSnapshot);
    }

    private List<AttemptItem> orderedItems(ExamAttempt a) {
        List<AttemptItem> items = new ArrayList<>(attemptItems(a));
        items.sort(Comparator.comparingInt(AttemptItem::position));
        if (a.getExam().isShuffleQuestions()) Collections.shuffle(items, new Random(a.getId()));
        return items;
    }

    private ExamDtos.AttemptView toAttemptView(ExamAttempt a) {
        ensureSnapshots(a.getExam());
        Map<Long, AttemptAnswer> saved = answerMap(a);
        List<ExamDtos.AttemptQuestion> questions = orderedItems(a).stream().map(item -> {
            AttemptAnswer answer = saved.get(item.question().getId());
            return new ExamDtos.AttemptQuestion(item.question().getId(), item.type(), item.stem(),
                    item.optionsJson(), item.points(), item.position(), answer == null ? null : answer.getAnswerText(),
                    item.type() == QuestionType.FILL_BLANK ? FillBlankGrading.blankCount(item.answer()) : 0);
        }).toList();
        return new ExamDtos.AttemptView(a.getId(), a.getExam().getId(), a.getExam().getTitle(), a.getExam().getStatus() == ExamStatus.CANCELLED ? AttemptStatus.CANCELLED : a.getStatus(), a.getStartedAt(),
                deadline(a), questions, Instant.now(), a.getExam().isBasicAntiCheat(), a.getFocusLossCount());
    }

    private Map<Long, AttemptAnswer> answerMap(ExamAttempt a) {
        return a.getAnswers().stream().collect(Collectors.toMap(v -> v.getQuestion().getId(), Function.identity()));
    }
    private List<AttemptItem> attemptItems(ExamAttempt attempt) {
        if (attempt.getPersonalPaper() != null) return attempt.getPersonalPaper().getQuestions().stream()
                .map(q -> new AttemptItem(q.getQuestion(), q.getSnapshotType(), q.getSnapshotStem(), q.getSnapshotOptionsJson(),
                        q.getSnapshotAnswer(), q.getSnapshotExplanation(), q.getPoints(), q.getPosition())).toList();
        return attempt.getExam().getQuestions().stream()
                .map(q -> new AttemptItem(q.getQuestion(), q.getSnapshotType(), q.getSnapshotStem(), q.getSnapshotOptionsJson(),
                        q.getSnapshotAnswer(), q.getSnapshotExplanation(), q.getPoints(), q.getPosition())).toList();
    }

    private void generatePersonalPapers(Exam exam) {
        exam.getPersonalPapers().clear();
        Set<String> chapters = parseChapters(exam.getChaptersJson());
        for (AppUser user : userRepository.findDistinctByRoles_CodeAndStatus("CANDIDATE", UserStatus.ACTIVE)) {
            if (!audienceAllows(exam, user)) continue;
            List<Question> wrong = unresolvedExamWrongQuestions(user.getId()).stream()
                    .filter(q -> q.getStatus() == QuestionStatus.ACTIVE)
                    .filter(q -> exam.getCourseCode().equals(q.getCourseCode()))
                    .filter(q -> exam.isAllWrongQuestions() || chapters.isEmpty() || chapters.contains(chapter(q)))
                    .limit(exam.isAllWrongQuestions() ? 1001 : Math.min(exam.getWrongQuestionLimit(), exam.getTotalScore())).toList();
            if (wrong.isEmpty()) continue;
            if (wrong.size() > 1000) throw new IllegalStateException("该课程有学员的考试错题超过1000道，请分场安排");
            if (wrong.size() > exam.getTotalScore()) throw new IllegalStateException("该课程有学员的全部考试错题为 " + wrong.size() + " 道，请将总分至少设为 " + wrong.size() + "，保证每题至少1分");
            wrong.forEach(this::ensureEligible);
            PersonalExamPaper paper = new PersonalExamPaper(); paper.setExam(exam); paper.setUser(user);
            int base = exam.getTotalScore() / wrong.size(), remainder = exam.getTotalScore() % wrong.size();
            for (int i = 0; i < wrong.size(); i++) {
                PersonalExamQuestion item = new PersonalExamQuestion(); item.setPaper(paper); item.setQuestion(wrong.get(i));
                item.setPosition(i + 1); item.setPoints(base + (i < remainder ? 1 : 0)); item.captureSnapshot();
                paper.getQuestions().add(item);
            }
            exam.getPersonalPapers().add(paper);
        }
        if (exam.getPersonalPapers().isEmpty()) throw new IllegalStateException("没有考生具备符合课程和章节范围的考试错题，暂不能发布");
    }

    private List<Question> unresolvedExamWrongQuestions(Long userId) {
        return wrongQuestions.unresolved(userId, "EXAM");
    }

    private boolean audienceAllows(Exam exam, AppUser user) {
        if (!privateAdmins.visible(user)) return false;
        if (user.getStatus() != UserStatus.ACTIVE || user.getRoles().stream().noneMatch(r -> r.effectiveAuthority().equals("CANDIDATE"))) return false;
        if (exam.isAudienceFrozen()) return exam.getAudienceUserIds().contains(user.getId());
        if (exam.getAudienceMode() == null || exam.getAudienceMode().equals("ALL")) return true;
        return exam.getAudienceUserIds().contains(user.getId());
    }

    private void freezeAudience(Exam exam) {
        if (exam.getMakeupSourceExamId() != null) {
            exam.setAudienceMode("USERS"); exam.getAudienceUserIds().clear();
            exam.getAudienceUserIds().addAll(failedCandidates(lockExam(exam.getMakeupSourceExamId())));
        }
        var active = userRepository.findAll().stream().filter(privateAdmins::visible)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE && u.getRoles().stream().anyMatch(r -> "CANDIDATE".equals(r.effectiveAuthority()))).toList();
        if (exam.getAudienceMode() == null || exam.getAudienceMode().equals("ALL")) {
            exam.getAudienceUserIds().clear();
            active.forEach(u -> exam.getAudienceUserIds().add(u.getId()));
            exam.setAudienceFrozen(true);
            return;
        }
        if (exam.getAudienceMode().equals("MAJORS")) {
            exam.getAudienceUserIds().clear();
            active.stream().filter(u -> exam.getAudienceMajors().contains(u.getProfessionalDirection())).forEach(u -> exam.getAudienceUserIds().add(u.getId()));
        } else {
            var activeIds = active.stream().map(AppUser::getId).collect(Collectors.toSet());
            if (!activeIds.containsAll(exam.getAudienceUserIds())) throw new IllegalArgumentException("指定人员中包含停用或非学员账号，请重新选择");
        }
        if (exam.getAudienceUserIds().isEmpty()) throw new IllegalArgumentException("所选发布范围没有启用的学员");
        exam.setAudienceFrozen(true);
    }

    private double percent(ExamDtos.GradeView g) { return g.score() * 100.0 / g.totalScore(); }

    private String normalizeChapters(List<String> chapters) {
        if (chapters == null) return null;
        String value = chapters.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().collect(Collectors.joining("\n"));
        return value.isBlank() ? null : value;
    }
    private Set<String> parseChapters(String value) { return value == null || value.isBlank() ? Set.of() : Arrays.stream(value.split("\\R")).collect(Collectors.toCollection(LinkedHashSet::new)); }
    private String chapter(Question q) { return q.getChapter() == null || q.getChapter().isBlank() ? "未分章" : q.getChapter(); }
    private record AttemptItem(Question question, QuestionType type, String stem, String optionsJson, String answer,
                               String explanation, int points, int position) {}
    private String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Exam findExam(Long id) { return examRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("考试不存在")); }
    private Exam lockExam(Long id) { return examRepository.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("考试不存在")); }
    private ExamAttempt lockAttempt(Long id) {
        ExamAttempt attempt = attemptRepository.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("答卷不存在"));
        // A preceding list query may have loaded this attempt before another transaction submitted it.
        // Refresh under the lock so close/resume/statistics never act on the stale in-progress state.
        entityManager.refresh(attempt, LockModeType.PESSIMISTIC_WRITE);
        return attempt;
    }
    private AppUser findUser(String number) { return userRepository.findByEmployeeNo(number).orElseThrow(() -> new IllegalArgumentException("账号不存在")); }
    private AppUser lockUser(String number) { return userRepository.findLockedByEmployeeNo(number).orElseThrow(() -> new IllegalArgumentException("账号不存在")); }
    private ExamAttempt ownedAttempt(Long id, String employeeNo) {
        ExamAttempt a = lockAttempt(id);
        if (!a.getUser().getEmployeeNo().equals(employeeNo)) throw new IllegalArgumentException("答卷不存在");
        return a;
    }
}
