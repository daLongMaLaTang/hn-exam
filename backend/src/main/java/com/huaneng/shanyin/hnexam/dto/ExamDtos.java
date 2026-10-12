package com.huaneng.shanyin.hnexam.dto;

import com.huaneng.shanyin.hnexam.domain.AttemptStatus;
import com.huaneng.shanyin.hnexam.domain.ExamStatus;
import com.huaneng.shanyin.hnexam.domain.GradingMethod;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Exam;
import com.huaneng.shanyin.hnexam.entity.ExamAttempt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ExamDtos {
    private ExamDtos() {}

    public record QuestionItem(@NotNull Long questionId, @Min(1) int points, @Min(1) int position) {}

    public record SaveRequest(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 100) String courseCode,
            @Size(max = 100) List<@Size(max = 100) String> chapters,
            @Size(max = 10000) String description,
            @Min(1) @Max(1440) int durationMinutes,
            @Min(1) int totalScore,
            @Min(0) int passScore,
            Instant startTime,
            Instant endTime,
            boolean allowRetake,
            @Min(1) @Max(20) int maxAttempts,
            boolean shuffleQuestions,
            boolean basicAntiCheat,
            boolean wrongQuestionPaper,
            @Min(1) @Max(200) int wrongQuestionLimit,
            @Valid List<QuestionItem> questions,
            boolean allWrongQuestions,
            @Pattern(regexp = "ALL|USERS|MAJORS") String audienceMode,
            @Size(max=10000) List<@NotNull Long> audienceUserIds,
            @Size(max=100) List<@NotBlank @Size(max=100) String> audienceMajors
    ) {
        public SaveRequest(String title, String courseCode, List<String> chapters, String description, int durationMinutes,
                           int totalScore, int passScore, Instant startTime, Instant endTime, boolean allowRetake,
                           int maxAttempts, boolean shuffleQuestions, boolean basicAntiCheat, boolean wrongQuestionPaper,
                           int wrongQuestionLimit, List<QuestionItem> questions) {
            this(title, courseCode, chapters, description, durationMinutes, totalScore, passScore, startTime, endTime,
                    allowRetake, maxAttempts, shuffleQuestions, basicAntiCheat, wrongQuestionPaper, wrongQuestionLimit,
                    questions, false, "ALL", List.of(), List.of());
        }
    }

    public record Summary(Long id, String title, String courseCode, String description, int durationMinutes, int totalScore,
                          int passScore, Instant startTime, Instant endTime, ExamStatus status,
                          boolean allowRetake, int maxAttempts, int questionCount,
                          boolean shuffleQuestions, boolean basicAntiCheat, List<String> chapters,
                          boolean wrongQuestionPaper, int wrongQuestionLimit, int assignedCandidateCount, String cancellationReason,
                          boolean allWrongQuestions, String audienceMode, Long makeupSourceExamId) {
        public static Summary from(Exam exam) {
            return new Summary(exam.getId(), exam.getTitle(), exam.getCourseCode(), exam.getDescription(), exam.getDurationMinutes(),
                    exam.getTotalScore(), exam.getPassScore(), exam.getStartTime(), exam.getEndTime(),
                    exam.getStatus(), false, 1, exam.getQuestions().size(),
                    exam.isShuffleQuestions(), exam.isBasicAntiCheat(), parseChapters(exam), exam.isWrongQuestionPaper(),
                    exam.getWrongQuestionLimit(), exam.getPersonalPapers().size(), exam.getCancellationReason(),
                    exam.isAllWrongQuestions(), exam.getAudienceMode() == null ? "ALL" : exam.getAudienceMode(), exam.getMakeupSourceExamId());
        }
    }

    public record AttemptQuestion(Long id, QuestionType type, String stem, String optionsJson,
                                  int points, int position, String savedAnswer, int blankCount) {}

    public record AttemptView(Long attemptId, Long examId, String examTitle, AttemptStatus status,
                              Instant startedAt, Instant deadline, List<AttemptQuestion> questions,
                              Instant serverTime, boolean basicAntiCheat, int focusLossCount) {}

    public record SaveAck(AttemptStatus status, Instant serverTime, int focusLossCount) {}

    public record SubmitRequest(@NotNull @Size(max = 1000) Map<Long, @Size(max = 10000) String> answers, @Min(0) int focusLossCount) {}

    public record Detail(Long id, String title, String courseCode, String description, int durationMinutes, int totalScore,
                         int passScore, Instant startTime, Instant endTime, ExamStatus status,
                         boolean allowRetake, int maxAttempts, boolean shuffleQuestions,
                         boolean basicAntiCheat, List<String> chapters, boolean wrongQuestionPaper,
                         int wrongQuestionLimit, int assignedCandidateCount, List<QuestionItem> questions,
                         boolean allWrongQuestions, String audienceMode, java.util.Set<Long> audienceUserIds, java.util.Set<String> audienceMajors, Long makeupSourceExamId) {
        public static Detail from(Exam e) {
            return new Detail(e.getId(), e.getTitle(), e.getCourseCode(), e.getDescription(), e.getDurationMinutes(), e.getTotalScore(),
                    e.getPassScore(), e.getStartTime(), e.getEndTime(), e.getStatus(), false,
                    1, e.isShuffleQuestions(), e.isBasicAntiCheat(), parseChapters(e), e.isWrongQuestionPaper(),
                    e.getWrongQuestionLimit(), e.getPersonalPapers().size(), e.getQuestions().stream()
                    .map(q -> new QuestionItem(q.getQuestion().getId(), q.getPoints(), q.getPosition())).toList(),
                    e.isAllWrongQuestions(), e.getAudienceMode() == null ? "ALL" : e.getAudienceMode(),
                    java.util.Set.copyOf(e.getAudienceUserIds()), java.util.Set.copyOf(e.getAudienceMajors()), e.getMakeupSourceExamId());
        }
    }
    public record ReviewQuestion(Long id, QuestionType type, String stem, String optionsJson, int points,
                                 int position, String savedAnswer, String correctAnswer, String explanation,
                                 Boolean correct, Integer awardedPoints, boolean objective,
                                 GradingMethod gradingMethod, String gradingComment,
                                 Integer aiSuggestedPoints, String aiSuggestionReason, Double semanticSimilarity, String similaritySource,
                                 Double aiSuggestedSimilarity, String aiModel, String aiStatus) {}
    public record Review(GradeView grade, List<ReviewQuestion> questions) {}
    public record ManualScore(@NotNull @Min(0) Integer awardedPoints, @Size(max = 2000) String comment,
                              @DecimalMin("0") @DecimalMax("100") Double semanticSimilarity, @Min(0) Integer correctBlanks) {
        public ManualScore(Integer points, String comment) { this(points, comment, null, null); }
        public ManualScore(Integer points, String comment, Double similarity) { this(points, comment, similarity, null); }
    }
    public record ManualGradeRequest(
            @NotNull @Size(max = 1000) Map<Long, @Valid ManualScore> scores,
            @Size(max = 2000) String overallComment
    ) {}
    public record ScoreBand(String label, long count) {}
    public record QuestionStatistic(Long questionId, String stem, QuestionType type, String chapter,
                                    long answeredCount, long wrongCount, double errorRate) {}
    public record Statistics(Long examId, long submittedCount, long participantCount, long passedCount,
                             double passRate, double averageScore, int highestScore, int lowestScore,
                             List<ScoreBand> scoreBands, List<QuestionStatistic> highErrorQuestions) {}

    private static List<String> parseChapters(Exam exam) {
        if (exam.getChaptersJson() == null || exam.getChaptersJson().isBlank()) return List.of();
        return java.util.Arrays.stream(exam.getChaptersJson().split("\\R")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    public record GradeView(Long attemptId, Long examId, String examTitle, Long userId, String employeeNo,
                            String userName, AttemptStatus status, Instant startedAt, Instant submittedAt,
                            Integer score, int totalScore, boolean passed, int focusLossCount,
                            int subjectiveQuestionCount, int pendingSubjectiveCount,
                            Instant gradedAt, String gradedByEmployeeNo, String gradingNote) {
        public static GradeView from(ExamAttempt attempt) {
            var subjectiveIds = attempt.getPersonalPaper() != null ? attempt.getPersonalPaper().getQuestions().stream()
                    .filter(item -> !isObjective(item.getSnapshotType())).map(item -> item.getQuestion().getId()).collect(java.util.stream.Collectors.toSet())
                    : attempt.getExam().getQuestions().stream()
                    .filter(item -> !isObjective(item.getSnapshotType() == null ? item.getQuestion().getType() : item.getSnapshotType()))
                    .map(item -> item.getQuestion().getId()).collect(java.util.stream.Collectors.toSet());
            attempt.getAnswers().stream().filter(a -> a.getAwardedPoints() == null && a.getAiStatus() != null && a.getAiStatus().startsWith("FILL_")).forEach(a -> subjectiveIds.add(a.getQuestion().getId()));
            long pending = attempt.getAnswers().stream()
                    .filter(answer -> subjectiveIds.contains(answer.getQuestion().getId()) && answer.getAwardedPoints() == null).count();
            pending += subjectiveIds.stream().filter(id -> attempt.getAnswers().stream().noneMatch(a -> a.getQuestion().getId().equals(id))).count();
            return new GradeView(attempt.getId(), attempt.getExam().getId(), attempt.getExam().getTitle(),
                    attempt.getUser().getId(), attempt.getUser().getEmployeeNo(), attempt.getUser().getName(),
                    attempt.getExam().getStatus() == ExamStatus.CANCELLED ? AttemptStatus.CANCELLED : attempt.getStatus(), attempt.getStartedAt(), attempt.getSubmittedAt(), attempt.getScore(),
                    attempt.getExam().getTotalScore(), Boolean.TRUE.equals(attempt.getPassed()),
                    attempt.getFocusLossCount(), subjectiveIds.size(), (int) pending, attempt.getGradedAt(),
                    attempt.getGradedByEmployeeNo(), attempt.getGradingNote());
        }

        private static boolean isObjective(QuestionType type) {
            return type != null && type.isAutomaticallyGraded();
        }
    }
}
