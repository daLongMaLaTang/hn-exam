package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.dto.LoginRequest;
import com.huaneng.shanyin.hnexam.dto.LoginResponse;
import com.huaneng.shanyin.hnexam.service.AuthService;
import com.huaneng.shanyin.hnexam.service.AuthStateService;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthStateService authStates;

    public record PasswordRequest(@NotBlank String oldPassword, @Size(min = 10, max = 64) String newPassword) {}

    @PostMapping("/password")
    @Transactional
    public LoginResponse password(@org.springframework.security.core.annotation.AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody PasswordRequest request) {
        var user = users.findByEmployeeNo(jwt.getSubject()).orElseThrow();
        if (!encoder.matches(request.oldPassword(), user.getPasswordHash())) throw new IllegalArgumentException("原密码不正确");
        if (request.newPassword() == null || !request.newPassword().matches("(?s)(?=.*[A-Za-z])(?=.*[0-9]).{10,64}")) throw new IllegalArgumentException("新密码须为10至64位，包含字母和数字");
        if (encoder.matches(request.newPassword(), user.getPasswordHash())) throw new IllegalArgumentException("新密码不能与原密码相同");
        user.setPasswordHash(encoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setAuthVersion(user.getAuthVersion() + 1);
        authStates.evictAfterCommit(user.getEmployeeNo());
        return authService.tokenFor(user);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@org.springframework.security.core.annotation.AuthenticationPrincipal Jwt jwt) {
        var user = users.findByEmployeeNo(jwt.getSubject()).orElseThrow();
        return Map.of("userId", user.getId(), "employeeNo", user.getEmployeeNo(), "name", user.getName(),
                "roles", user.getRoles().stream().map(role -> role.effectiveAuthority()).toList(),
                "mustChangePassword", user.isMustChangePassword());
    }
}

