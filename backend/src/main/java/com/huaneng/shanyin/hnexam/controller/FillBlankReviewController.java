package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.service.FillBlankReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/exams") @RequiredArgsConstructor
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class FillBlankReviewController {
    private final FillBlankReviewService service;
    public record Correction(boolean reopenPublished) {}
    public record Publish(int expectedCount) {}
    public record Count(int affected) {}
    @GetMapping("/{id}/blank-review")
    public FillBlankReviewService.Workspace workspace(@PathVariable Long id) {return service.workspace(id);}
    @PostMapping("/{id}/blank-review/decisions")
    public Count decide(@PathVariable Long id,@RequestBody FillBlankReviewService.DecisionRequest request,@AuthenticationPrincipal Jwt jwt) {
        return new Count(service.decide(id,request,jwt.getSubject()));
    }
    @PostMapping("/{id}/blank-review/decisions/{decisionId}/revoke")
    public java.util.Map<String,String> revoke(@PathVariable Long id,@PathVariable Long decisionId,@RequestBody Correction request,@AuthenticationPrincipal Jwt jwt) {
        service.revoke(id,decisionId,request.reopenPublished(),jwt.getSubject());
        return java.util.Map.of("status","ok");
    }
    @PostMapping("/{id}/blank-review/publish")
    public Count publish(@PathVariable Long id,@RequestBody Publish request,@AuthenticationPrincipal Jwt jwt) {
        return new Count(service.publish(id,jwt.getSubject(),request.expectedCount()));
    }
    @PostMapping("/blank-rules/{id}/disable")
    public java.util.Map<String,String> disable(@PathVariable Long id,@AuthenticationPrincipal Jwt jwt) {service.disableRule(id,jwt.getSubject());return java.util.Map.of("status","ok");}
    @PostMapping("/blank-rules/{id}/withdraw-scores")
    public Count withdraw(@PathVariable Long id,@RequestBody Correction request,@AuthenticationPrincipal Jwt jwt) {
        return new Count(service.withdrawRuleScores(id,request.reopenPublished(),jwt.getSubject()));
    }
}
