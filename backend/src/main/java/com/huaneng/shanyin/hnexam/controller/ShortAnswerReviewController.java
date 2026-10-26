package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.security.Principal;
import java.util.Map;

@RestController @RequiredArgsConstructor @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class ShortAnswerReviewController {
    private final ShortAnswerRubricService rubrics;
    private final ShortAnswerReviewService review;
    private final ShortAnswerAutomationService automation;
    @PostMapping("/api/exams/{id}/short-review/jobs")
    public ShortAnswerAutomationService.Progress start(@PathVariable Long id,Principal p) {return automation.start(id,p.getName());}
    @GetMapping("/api/exams/{id}/short-review/jobs")
    public java.util.List<ShortAnswerAutomationService.Progress> progress(@PathVariable Long id) {return automation.progress(id);}
    @PostMapping("/api/exams/{id}/short-review/jobs/{jobId}/retry")
    public ShortAnswerAutomationService.Progress retry(@PathVariable Long id,@PathVariable Long jobId,Principal p) {return automation.retry(id,jobId,p.getName());}
    @GetMapping("/api/questions/{id}/rubric")
    public ShortAnswerRubricService.View get(@PathVariable Long id) {return rubrics.view(id);}
    @PutMapping("/api/questions/{id}/rubric")
    public ShortAnswerRubricService.View save(@PathVariable Long id,@RequestBody ShortAnswerRubricService.Save r,Principal p) {return rubrics.save(id,r,p.getName());}
    @PostMapping("/api/questions/{id}/rubric/generate")
    public ShortAnswerRubricService.View generate(@PathVariable Long id,@RequestBody ShortAnswerRubricService.Expected r,Principal p) {return rubrics.generate(id,r,p.getName());}
    @PostMapping("/api/questions/{id}/rubric/confirm")
    public ShortAnswerRubricService.View confirm(@PathVariable Long id,@RequestBody ShortAnswerRubricService.Expected r,Principal p) {return rubrics.confirm(id,r,p.getName());}
    @GetMapping("/api/exams/{id}/short-review")
    public ShortAnswerReviewService.Workspace workspace(@PathVariable Long id) {return review.workspace(id);}
    @PostMapping("/api/exams/attempts/{id}/short-ai/{qid}")
    public Map<String,String> suggest(@PathVariable Long id,@PathVariable Long qid,@RequestBody(required=false) ShortAnswerReviewService.Generate expected,Principal p) {return automation.startForAttempt(id,p.getName());}
    @PostMapping("/api/exams/{id}/short-review/accept")
    public Map<String,Integer> accept(@PathVariable Long id,@RequestBody ShortAnswerReviewService.Accept r,Principal p) {return Map.of("affected",review.accept(id,r,p.getName()));}
    @PostMapping("/api/exams/{id}/short-review/manual")
    public Map<String,String> manual(@PathVariable Long id,@RequestBody ShortAnswerReviewService.Manual r,Principal p) {review.manual(id,r,p.getName());return Map.of("status","ok");}
    @PostMapping("/api/exams/{id}/short-review/apply-suggestions")
    public Map<String,Integer> applySuggestions(@PathVariable Long id,@RequestBody ShortAnswerReviewService.ApplySuggestions r,Principal p) {return Map.of("affected",review.applySuggestions(id,r,p.getName()));}
    @PostMapping("/api/exams/{id}/short-review/undo")
    public Map<String,String> undo(@PathVariable Long id,@RequestBody ShortAnswerReviewService.Undo r,Principal p) {review.undo(id,r,p.getName());return Map.of("status","ok");}
}
