package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.repository.QuestionRepository;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import com.huaneng.shanyin.hnexam.repository.ExamRepository;
import com.huaneng.shanyin.hnexam.repository.ExamAttemptRepository;
import com.huaneng.shanyin.hnexam.repository.PracticeRecordRepository;
import com.huaneng.shanyin.hnexam.domain.AttemptStatus;
import com.huaneng.shanyin.hnexam.domain.ExamStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final UserRepository userRepository;
    private final QuestionRepository questionRepository;
    private final ExamRepository exams;
    private final ExamAttemptRepository attempts;
    private final PracticeRecordRepository practices;
    private final ProfileController profiles;
    private final com.huaneng.shanyin.hnexam.service.WrongQuestionService wrongQuestions;

    public record MonthlyPerson(Long id, String employeeNo, String name, long practiceSeconds, long examCount,
                                double averagePercent, double passRate, long entered, long resolved, long unresolved, double resolutionRate) {}
    @GetMapping("/monthly-learning")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public java.util.List<MonthlyPerson> monthly(@org.springframework.web.bind.annotation.RequestParam(required=false) String month) {
        String selected;
        try { selected = (month == null ? java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")) : java.time.YearMonth.parse(month)).toString(); }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("月份格式应为 YYYY-MM"); }
        return userRepository.findAll().stream().map(u -> {
            var analysis = profiles.get(u.getId()).months().stream().filter(m -> m.month().equals(selected)).findFirst()
                    .orElse(new ProfileController.Month(selected, 0, 0, 0, 0));
            var wrong = wrongQuestions.summary(u.getId(), "EXAM");
            return new MonthlyPerson(u.getId(), u.getEmployeeNo(), u.getName(), analysis.practiceSeconds(), analysis.count(),
                    analysis.averagePercent(), analysis.passRate(), wrong.entered(), wrong.resolved(), wrong.unresolved(), wrong.resolutionRate());
        }).sorted(java.util.Comparator.comparingLong(MonthlyPerson::practiceSeconds).reversed().thenComparing(MonthlyPerson::employeeNo)).toList();
    }

    public record ExamTrend(Long id, String title, String course, java.time.Instant date, long participants,
                            long graded, Double averagePercent, Double passRate) {}
    @GetMapping("/exam-trends")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public java.util.List<ExamTrend> trends() {
        var counts = attempts.trendParticipants().stream().collect(java.util.stream.Collectors.toMap(ExamAttemptRepository.Participation::getExamId, x -> x));
        var grades = attempts.trendGrades().stream().collect(java.util.stream.Collectors.toMap(ExamAttemptRepository.TrendGrade::getExamId, x -> x));
        return exams.findAllByOrderByIdDesc().stream().filter(e -> e.getStatus() == ExamStatus.PUBLISHED || e.getStatus() == ExamStatus.CLOSED).map(e -> {
            var g = grades.get(e.getId()); var c = counts.get(e.getId());
            return new ExamTrend(e.getId(), e.getTitle(), e.getCourseCode(), e.getStartTime(), c == null ? 0 : c.getParticipants(),
                    g == null ? 0 : g.getGraded(), g == null ? null : g.getAverage(), g == null ? null : 100.0 * g.getPassed() / g.getGraded());
        }).toList();
    }

    @GetMapping("/summary")
    public Map<String, Long> summary(@AuthenticationPrincipal Jwt jwt) {
        var user = userRepository.findByEmployeeNo(jwt.getSubject()).orElseThrow();
        if (user.getRoles().stream().allMatch(r -> r.effectiveAuthority().equals("CANDIDATE"))) {
            return Map.of("practiceCount", practices.countByUserId(user.getId()),
                    "correctCount", practices.countByUserIdAndCorrectTrue(user.getId()),
                    "examCount", attempts.countByUserIdAndStatusAndExamStatusNot(user.getId(), AttemptStatus.GRADED, ExamStatus.CANCELLED),
                    "passedCount", attempts.countPassedExamsByUserId(user.getId(), ExamStatus.CANCELLED));
        }
        return Map.of(
                "userCount", userRepository.count(),
                "questionCount", questionRepository.countByDeletedFalse(),
                "pendingQuestionCount", questionRepository.countByStatus(QuestionStatus.PENDING_REVIEW),
                "activeQuestionCount", questionRepository.countByStatus(QuestionStatus.ACTIVE),
                "examCount", exams.count(), "gradedCount", attempts.countByStatusAndExamStatusNot(AttemptStatus.GRADED, com.huaneng.shanyin.hnexam.domain.ExamStatus.CANCELLED),
                "pendingGradingCount", attempts.countByStatusAndExamStatusNot(AttemptStatus.SUBMITTED, com.huaneng.shanyin.hnexam.domain.ExamStatus.CANCELLED)
        );
    }
}

