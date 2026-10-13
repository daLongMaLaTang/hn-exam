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
    @Value("${app.login.max-account-failures:20}") private int maxAccountFailures = 20;
    @Value("${app.login.max-ip-failures:200}") private int maxIpFailures = 200;
    @Value("${app.login.max-local-entries:20000}") private int maxLocalEntries = 20000;
    @Value("${app.login.failure-window-seconds:900}") private long windowSeconds;

    public void checkAllowed(String employeeNo, String remoteAddress) {
        check(key(employeeNo, remoteAddress), maxFailures);
        check(accountKey(employeeNo), maxAccountFailures);
        check(ipKey(remoteAddress), maxIpFailures);
    }

    private void check(String key, int limit) {
        FailureWindow value = local.get(key);
        if (value != null && value.expiresAt() <= System.currentTimeMillis()) local.remove(key, value);
        if (value != null && value.expiresAt() > System.currentTimeMillis() && value.count() >= limit) {
            throw new TooManyLoginAttemptsException();
        }
        redis.get(key).ifPresent(count -> {
            try { if (Integer.parseInt(count) >= limit) throw new TooManyLoginAttemptsException(); }
            catch (NumberFormatException ignored) { redis.delete(key); }
        });
    }

    public void failed(String employeeNo, String remoteAddress) {
        increment(key(employeeNo, remoteAddress));
        increment(accountKey(employeeNo));
        increment(ipKey(remoteAddress));
    }

    private void increment(String key) {
        long expiresAt = System.currentTimeMillis() + windowSeconds * 1000L;
        synchronized (local) {
            if (!local.containsKey(key) && local.size() >= maxLocalEntries) {
                cleanupExpired();
                if (local.size() >= maxLocalEntries) throw new TooManyLoginAttemptsException();
            }
            local.compute(key, (ignored, value) -> value == null || value.expiresAt() <= System.currentTimeMillis()
                    ? new FailureWindow(1, expiresAt) : new FailureWindow(Math.min(Integer.MAX_VALUE - 1, value.count()) + 1, value.expiresAt()));
        }
        redis.increment(key, Duration.ofSeconds(windowSeconds));
    }

    public void succeeded(String employeeNo, String remoteAddress) {
        String key = key(employeeNo, remoteAddress);
        local.remove(key);
        redis.delete(key);
        // A successful login must not clear the IP-wide password-spray budget.
        local.remove(accountKey(employeeNo));
        redis.delete(accountKey(employeeNo));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        local.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private String accountKey(String employeeNo) { return scopedKey("account", employeeNo, ""); }
    private String ipKey(String remoteAddress) { return scopedKey("ip", "", remoteAddress); }

    private String key(String employeeNo, String remoteAddress) {
        return scopedKey("pair", employeeNo, remoteAddress);
    }
    private String scopedKey(String scope, String employeeNo, String remoteAddress) {
        String raw = scope + "|" + String.valueOf(employeeNo).trim().toLowerCase(java.util.Locale.ROOT) + "|" + String.valueOf(remoteAddress);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
            return redis.key("login:fail:" + digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
