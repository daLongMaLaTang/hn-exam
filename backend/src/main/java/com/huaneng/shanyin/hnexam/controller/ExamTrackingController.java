package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.dto.ExamTrackingDtos.*;
import com.huaneng.shanyin.hnexam.service.ExamTrackingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ExamTrackingController {
    private final ExamTrackingService service;
    @GetMapping("/exams/{id}/tracking")
    @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST')")
    public Tracking tracking(@PathVariable Long id) { return service.tracking(id); }
    @PostMapping("/exams/{id}/reminders")
    @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN')")
    public ReminderResult remind(@PathVariable Long id, @Valid @RequestBody ReminderRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.remind(id, request.userIds(), jwt.getSubject());
    }
    @GetMapping("/notifications") @PreAuthorize("hasAuthority('CANDIDATE')")
    public Inbox inbox(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page) { return service.inbox(jwt.getSubject(), page); }
    @PostMapping("/notifications/{id}/read") @PreAuthorize("hasAuthority('CANDIDATE')")
    public Notice read(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) { return service.read(id, jwt.getSubject()); }
}
