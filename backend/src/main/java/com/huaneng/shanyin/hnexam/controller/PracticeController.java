package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.domain.AttemptStatus;
import com.huaneng.shanyin.hnexam.dto.QuestionDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController @RequestMapping("/api/practice") @RequiredArgsConstructor @PreAuthorize("hasAuthority('CANDIDATE')")
@Transactional(readOnly = true)
public class PracticeController {
    private final QuestionRepository questionRepository;
    private final UserRepository userRepository;
    private final com.huaneng.shanyin.hnexam.service.WrongQuestionService wrongQuestions;
    private final ExamAttemptRepository attemptRepository;
    private final com.huaneng.shanyin.hnexam.service.PracticeSubmissionService submissions;
    @GetMapping("/questions") public List<QuestionDtos.CandidateView> questions(@AuthenticationPrincipal Jwt jwt) {
        Set<Long> protectedIds = protectedQuestionIds(userRepository.findByEmployeeNo(jwt.getSubject()).orElseThrow().getId());
        return questionRepository.findByStatusOrderByIdDesc(QuestionStatus.ACTIVE).stream()
                .filter(q -> !protectedIds.contains(q.getId()))
                .map(QuestionDtos.CandidateView::from).toList();
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    @PostMapping("/questions/{id}/submit") public QuestionDtos.PracticeResult submit(@PathVariable Long id, @Valid @RequestBody QuestionDtos.PracticeSubmit request, @AuthenticationPrincipal Jwt jwt) {
        return submissions.submit(id, request.answer(), jwt.getSubject());
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
