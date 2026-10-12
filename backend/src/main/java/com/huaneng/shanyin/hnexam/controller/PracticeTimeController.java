package com.huaneng.shanyin.hnexam.controller;
import com.huaneng.shanyin.hnexam.service.PracticeTimeService; import jakarta.validation.Valid; import jakarta.validation.constraints.*; import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;
@RestController @RequiredArgsConstructor @PreAuthorize("hasAuthority('CANDIDATE')")
public class PracticeTimeController {
    private final PracticeTimeService service;
    public record Heartbeat(@NotBlank @Size(max=64) String session,boolean visible,@Min(0) @Max(120) int idleSeconds) {}
    @PostMapping("/api/practice/heartbeat") public PracticeTimeService.Time heartbeat(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Heartbeat r) {return service.heartbeat(jwt.getSubject(),r.session(),r.visible(),r.idleSeconds());}
}
