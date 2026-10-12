package com.huaneng.shanyin.hnexam.dto;

import java.time.Instant;
import java.util.Set;

public record LoginResponse(
        String accessToken,
        Instant expiresAt,
        Long userId,
        String employeeNo,
        String name,
        Set<String> roles,
        boolean mustChangePassword
) {}

