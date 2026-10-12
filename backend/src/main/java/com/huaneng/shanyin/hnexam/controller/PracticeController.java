package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.domain.AttemptStatus;
import com.huaneng.shanyin.hnexam.dto.QuestionDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.AnswerGrader;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.stream.Collectors;

@RestController @RequestMapping("/api/practice") @RequiredArgsConstructor @PreAuthorize("hasAuthority('CANDIDATE')")
@Transactional
public class PracticeController {
    private final QuestionRepository questionRepository;
    private final PracticeRecordRepository practiceRepository;
    private final UserRepository userRepository;
    private final AnswerGrader answerGrader;
    private final com.huaneng.shanyin.hnexam.service.FillBlankSemanticService fillSemantic;
    private final com.huaneng.shanyin.hnexam.service.WrongQuestionService wrongQuestions;
    private final ExamAttemptRepository attemptRepository;
    @GetMapping("/questions") public List<QuestionDtos.CandidateView> questions(@AuthenticationPrincipal Jwt jwt) {
        Set<Long> protectedIds = protectedQuestionIds(userRepository.findByEmployeeNo(jwt.getSubject()).orElseThrow().getId());
        return questionRepository.findByStatusOrderByIdDesc(QuestionStatus.ACTIVE).stream()
                .filter(q -> !protectedIds.contains(q.getId()))
                .map(QuestionDtos.CandidateView::from).toList();
    }
    @PostMapping("/questions/{id}/submit") public QuestionDtos.PracticeResult submit(@PathVariable Long id, @Valid @RequestBody QuestionDtos.PracticeSubmit request, @AuthenticationPrincipal Jwt jwt) {
        Question q = questionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("试题不存在"));
        if (q.getStatus() != QuestionStatus.ACTIVE) throw new IllegalStateException("试题尚未启用");
        AppUser user = userRepository.findByEmployeeNo(jwt.getSubject()).orElseThrow();
        if (protectedQuestionIds(user.getId()).contains(id)) throw new IllegalStateException("考试进行中，暂不能查看本次考试题目的刷题反馈");
        boolean assessed = answerGrader.isObjective(q);
        boolean correct = assessed && answerGrader.isCorrect(q, request.answer());
        if (q.getType() == com.huaneng.shanyin.hnexam.domain.QuestionType.FILL_BLANK) {
            try { correct = fillSemantic.grade(null, q.getId(), q.getStem(), q.getAnswer(), request.answer(), 1).correct(); }
            catch (RuntimeException e) { assessed = false; correct = false; }
        }
        PracticeRecord record = new PracticeRecord(); record.setUser(user); record.setQuestion(q); record.setAnswerText(request.answer()); record.setCorrect(correct); record.setAnsweredAt(Instant.now());
        record.setAssessed(assessed);
        practiceRepository.save(record);
        return new QuestionDtos.PracticeResult(assessed ? correct : null, q.getAnswer(), q.getExplanation());
    }
    @GetMapping("/wrong") public List<QuestionDtos.CandidateView> wrong(@AuthenticationPrincipal Jwt jwt) { return dailyWrong(jwt); }
    @GetMapping("/wrong/daily") public List<QuestionDtos.CandidateView> dailyWrong(@AuthenticationPrincipal Jwt jwt) { return unresolved(jwt, "DAILY"); }
    @GetMapping("/wrong/exam") public List<QuestionDtos.CandidateView> examWrong(@AuthenticationPrincipal Jwt jwt) { return unresolved(jwt, "EXAM"); }
    @GetMapping("/wrong/summary") public com.huaneng.shanyin.hnexam.service.WrongQuestionService.Summary summary(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="DAILY") String source) {
        if (!Set.of("DAILY", "EXAM").contains(source)) throw new IllegalArgumentException("错题来源无效");
        return wrongQuestions.summary(userRepository.findByEmployeeNo(jwt.getSubject()).orElseThrow().getId(), source);
    }
    private List<QuestionDtos.CandidateView> unresolved(Jwt jwt, String source) {
        Long userId = userRepository.findByEmployeeNo(jwt.getSubject()).orElseThrow().getId();
        Set<Long> protectedIds = protectedQuestionIds(userId);
        return wrongQuestions.unresolved(userId, source).stream().filter(q -> q.getStatus() == QuestionStatus.ACTIVE && !protectedIds.contains(q.getId())).map(QuestionDtos.CandidateView::from).toList();
    }
    private Set<Long> protectedQuestionIds(Long userId) {
        return attemptRepository.findByUserIdAndStatus(userId, AttemptStatus.IN_PROGRESS).stream()
                .flatMap(a -> a.getPersonalPaper() == null
                        ? a.getExam().getQuestions().stream().map(q -> q.getQuestion().getId())
                        : a.getPersonalPaper().getQuestions().stream().map(q -> q.getQuestion().getId()))
                .collect(Collectors.toSet());
    }
}
