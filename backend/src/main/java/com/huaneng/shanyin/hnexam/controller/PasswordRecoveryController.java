package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.domain.UserStatus;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import jakarta.persistence.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class PasswordRecoveryController {
    private final UserRepository users;
    private final UserController accounts;
    @PersistenceContext private EntityManager em;
    public record Application(@NotBlank @Size(max=32) String employeeNo,@NotBlank @Size(max=80) String name) {}
    public record View(Long id,String employeeNo,String name,String department,Instant requestedAt) {}
    private boolean eligible(AppUser u) {
        return u.getStatus()==UserStatus.ACTIVE && !u.isPrivateAdministrator() && !u.getRoles().isEmpty()
            && u.getRoles().stream().allMatch(r -> "CANDIDATE".equals(r.effectiveAuthority()));
    }
    @PostMapping("/api/auth/password-recovery") @Transactional
    public Map<String,Boolean> submit(@Valid @RequestBody Application input) {
        var found=users.findByEmployeeNo(input.employeeNo().trim());
        if(found.isPresent() && found.get().getName().equals(input.name().trim())) {
            var user=users.findLockedById(found.get().getId()).orElseThrow();
            if(eligible(user)) {
                var pending=em.createQuery("select r from PasswordResetRequest r where r.user=:u and r.state='PENDING'",PasswordResetRequest.class)
                    .setParameter("u",user).getResultList();
                if(pending.isEmpty()) {
                    var row=new PasswordResetRequest(); row.setUser(user); row.setAuthVersion(user.getAuthVersion()); em.persist(row);
                }
            }
        }
        return Map.of("received",true);
    }
    @GetMapping("/api/users/password-requests") @PreAuthorize("hasAuthority('SYSTEM_ADMIN')") @Transactional(readOnly=true)
    public List<View> pending() {
        return em.createQuery("select r from PasswordResetRequest r join fetch r.user where r.state='PENDING' order by r.id",PasswordResetRequest.class)
            .getResultList().stream().map(r->new View(r.getId(),r.getUser().getEmployeeNo(),r.getUser().getName(),r.getUser().getDepartment(),r.getRequestedAt())).toList();
    }
    @PostMapping("/api/users/password-requests/{id}/{decision}") @PreAuthorize("hasAuthority('SYSTEM_ADMIN')") @Transactional
    public Object review(@PathVariable Long id,@PathVariable String decision,@AuthenticationPrincipal Jwt jwt,HttpServletResponse response) {
        var row=em.find(PasswordResetRequest.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null || !"PENDING".equals(row.getState())) throw new IllegalStateException("申请已经处理，请刷新列表");
        Object result=Map.of("reviewed",true);
        if("approve".equals(decision)) {
            var user=users.findLockedById(row.getUser().getId()).orElseThrow();
            if(!eligible(user) || user.getAuthVersion()!=row.getAuthVersion()) throw new IllegalStateException("账号已变更，请驳回并让学员重新申请");
            result=accounts.reset(user.getId(),null,jwt,response);
            row.setState("APPROVED");
        } else if("reject".equals(decision)) row.setState("REJECTED");
        else throw new IllegalArgumentException("无效审核操作");
        row.setReviewedBy(jwt.getSubject()); row.setReviewedAt(Instant.now());
        return result;
    }
}
