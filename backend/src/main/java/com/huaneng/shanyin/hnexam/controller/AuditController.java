package com.huaneng.shanyin.hnexam.controller;
import com.huaneng.shanyin.hnexam.entity.AuditEvent;
import com.huaneng.shanyin.hnexam.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/audit") @RequiredArgsConstructor
public class AuditController {
    private final AuditEventRepository events;
    @GetMapping @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public List<AuditEvent> list() { return events.findTop200ByOrderByOccurredAtDesc(); }
}
