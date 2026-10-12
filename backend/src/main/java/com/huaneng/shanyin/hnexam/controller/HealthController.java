package com.huaneng.shanyin.hnexam.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import com.huaneng.shanyin.hnexam.service.RedisSupport;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {
    private final RedisSupport redis;
    @GetMapping
    public Map<String, Object> health() {
        String redisState = !redis.isEnabled() ? "DISABLED" : redis.available() ? "UP" : "FALLBACK";
        return Map.of("status", "UP", "time", Instant.now(), "redis", redisState);
    }
}

