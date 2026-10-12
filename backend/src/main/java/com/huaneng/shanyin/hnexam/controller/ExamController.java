package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.service.ExamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ExamController {
    private final ExamService examService;
    private final com.huaneng.shanyin.hnexam.repository.UserRepository users;
    public record AudienceOption(Long id, String employeeNo, String name, String professionalDirection) {}
    @GetMapping("/exams/audience-options") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public List<AudienceOption> audienceOptions() {
        return users.findDistinctByRoles_CodeAndStatus("CANDIDATE", com.huaneng.shanyin.hnexam.domain.UserStatus.ACTIVE).stream()
                .map(u -> new AudienceOption(u.getId(),u.getEmployeeNo(),u.getName(),u.getProfessionalDirection())).toList();
    }

    @GetMapping("/exams") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public List<ExamDtos.Summary> adminList() { return examService.list(false); }
    @GetMapping("/exams/{id}") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public ExamDtos.Detail detail(@PathVariable Long id) { return examService.detail(id); }
    @PostMapping("/exams") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public ExamDtos.Summary create(@Valid @RequestBody ExamDtos.SaveRequest request) { return examService.create(request); }
    @PostMapping("/exams/{id}/makeup") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN')")
    public ExamDtos.Summary makeup(@PathVariable Long id) { return examService.createMakeup(id); }
    @PutMapping("/exams/{id}") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public ExamDtos.Summary update(@PathVariable Long id, @Valid @RequestBody ExamDtos.SaveRequest request) { return examService.update(id, request); }
    @PostMapping("/exams/{id}/publish") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN')")
    public ExamDtos.Summary publish(@PathVariable Long id) { return examService.publish(id); }
    @PostMapping("/exams/{id}/close") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN')")
    public ExamDtos.Summary close(@PathVariable Long id) { return examService.close(id); }
    @GetMapping("/exams/{id}/results") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public List<ExamDtos.GradeView> results(@PathVariable Long id) { return examService.results(id); }
    @GetMapping("/exams/{id}/statistics") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public ExamDtos.Statistics statistics(@PathVariable Long id) { return examService.statistics(id); }
    @GetMapping("/exams/attempts/{id}/review") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public ExamDtos.Review adminReview(@PathVariable Long id) { return examService.review(id, null); }
    @PostMapping("/exams/attempts/{id}/grade") @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ExamDtos.Review manualGrade(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ExamDtos.ManualGradeRequest request) {
        return examService.manualGrade(id, jwt.getSubject(), request);
    }

    public record CancelRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String reason) {}
    @PostMapping("/exams/{id}/cancel") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN')")
    public ExamDtos.Summary cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request, @AuthenticationPrincipal Jwt jwt) { return examService.cancel(id, request.reason(), jwt.getSubject()); }
    @GetMapping("/candidate/time") @PreAuthorize("hasAuthority('CANDIDATE')")
    public java.util.Map<String, java.time.Instant> time() { return java.util.Map.of("serverTime", java.time.Instant.now()); }
    @GetMapping("/candidate/exams") @PreAuthorize("hasAuthority('CANDIDATE')")
    public List<ExamDtos.Summary> candidateExams(@AuthenticationPrincipal Jwt jwt) { return examService.candidateList(jwt.getSubject()); }
    @PostMapping("/candidate/exams/{id}/start") @PreAuthorize("hasAuthority('CANDIDATE')")
    public ExamDtos.AttemptView start(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) { return examService.start(id, jwt.getSubject()); }
    @GetMapping("/candidate/attempts/{id}") @PreAuthorize("hasAuthority('CANDIDATE')")
    public ExamDtos.AttemptView attempt(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) { return examService.getAttempt(id, jwt.getSubject()); }
    @PutMapping("/candidate/attempts/{id}/answers") @PreAuthorize("hasAuthority('CANDIDATE')")
    public ExamDtos.SaveAck saveAnswers(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ExamDtos.SubmitRequest request) { return examService.saveAnswers(id, jwt.getSubject(), request); }
    @GetMapping("/candidate/attempts/{id}/review") @PreAuthorize("hasAuthority('CANDIDATE')")
    public ExamDtos.Review review(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) { return examService.review(id, jwt.getSubject()); }
    @PostMapping("/candidate/attempts/{id}/submit") @PreAuthorize("hasAuthority('CANDIDATE')")
    public ExamDtos.GradeView submit(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ExamDtos.SubmitRequest request) { return examService.submit(id, jwt.getSubject(), request); }
    @GetMapping("/candidate/grades") @PreAuthorize("hasAuthority('CANDIDATE')")
    public List<ExamDtos.GradeView> grades(@AuthenticationPrincipal Jwt jwt) {
        return examService.grades(jwt.getSubject());
    }
}
