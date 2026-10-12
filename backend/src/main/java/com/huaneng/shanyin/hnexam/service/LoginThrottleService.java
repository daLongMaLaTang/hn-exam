package com.huaneng.shanyin.hnexam.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class LoginThrottleService {
    private record FailureWindow(int count, long expiresAt) {}
    private final RedisSupport redis;
    private final ConcurrentHashMap<String, FailureWindow> local = new ConcurrentHashMap<>();

    @Value("${app.login.max-failures:8}") private int maxFailures;
    @Value("${app.login.failure-window-seconds:900}") private long windowSeconds;

    public void checkAllowed(String employeeNo, String remoteAddress) {
        String key = key(employeeNo, remoteAddress);
        FailureWindow value = local.get(key);
        if (value != null && value.expiresAt() > System.currentTimeMillis() && value.count() >= maxFailures) {
            throw new TooManyLoginAttemptsException();
        }
        redis.get(key).ifPresent(count -> {
            try { if (Integer.parseInt(count) >= maxFailures) throw new TooManyLoginAttemptsException(); }
            catch (NumberFormatException ignored) { redis.delete(key); }
        });
    }

    public void failed(String employeeNo, String remoteAddress) {
        String key = key(employeeNo, remoteAddress);
        long expiresAt = System.currentTimeMillis() + windowSeconds * 1000L;
        local.compute(key, (ignored, value) -> value == null || value.expiresAt() <= System.currentTimeMillis()
                ? new FailureWindow(1, expiresAt) : new FailureWindow(value.count() + 1, value.expiresAt()));
        redis.increment(key, Duration.ofSeconds(windowSeconds));
    }

    public void succeeded(String employeeNo, String remoteAddress) {
        String key = key(employeeNo, remoteAddress);
        local.remove(key);
        redis.delete(key);
    }

    private String key(String employeeNo, String remoteAddress) {
        String raw = String.valueOf(employeeNo).trim().toLowerCase() + "|" + String.valueOf(remoteAddress);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
            return redis.key("login:fail:" + digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
