package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.dto.LoginRequest;
import com.huaneng.shanyin.hnexam.dto.LoginResponse;
import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final UserRepository userRepository;
    private final LoginThrottleService loginThrottle;

    @Value("${app.jwt.ttl-seconds}")
    private long ttlSeconds;

    public LoginResponse login(LoginRequest request, String remoteAddress) {
        String employeeNo = request.employeeNo().trim();
        loginThrottle.checkAllowed(employeeNo, remoteAddress);
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(employeeNo, request.password()));
            AppUser user = userRepository.findByEmployeeNo(employeeNo).orElseThrow();
            loginThrottle.succeeded(employeeNo, remoteAddress);
            return tokenFor(user);
        } catch (AuthenticationException exception) {
            loginThrottle.failed(employeeNo, remoteAddress);
            throw exception;
        }
    }

    public LoginResponse tokenFor(AppUser user) {
        Set<String> roles = user.getRoles().stream().map(role -> role.effectiveAuthority()).collect(Collectors.toSet());
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("hn-exam")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getEmployeeNo())
                .claim("userId", user.getId())
                .claim("name", user.getName())
                .claim("roles", roles)
                .claim("version", user.getAuthVersion())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, expiresAt, user.getId(), user.getEmployeeNo(), user.getName(), roles,
                user.isMustChangePassword());
    }
}

