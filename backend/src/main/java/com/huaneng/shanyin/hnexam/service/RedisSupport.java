package com.huaneng.shanyin.hnexam.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small fault-tolerant Redis boundary. Exam answers are never stored here: Redis is only
 * an acceleration and abuse-prevention layer, so a Redis outage cannot lose an answer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisSupport {
    private final StringRedisTemplate redis;
    private final AtomicBoolean warningLogged = new AtomicBoolean();

    @Value("${app.redis.enabled:false}")
    private boolean enabled;
    @Value("${app.redis.key-prefix:hnexam}")
    private String keyPrefix;

    public boolean isEnabled() { return enabled; }

    public String key(String suffix) { return keyPrefix + ":" + suffix; }

    public Optional<String> get(String key) {
        if (!enabled) return Optional.empty();
        try {
            String value = redis.opsForValue().get(key);
            recovered();
            return Optional.ofNullable(value);
        } catch (RuntimeException ex) {
            unavailable(ex);
            return Optional.empty();
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key, value, ttl);
            recovered();
        } catch (RuntimeException ex) { unavailable(ex); }
    }

    public void delete(String key) {
        if (!enabled) return;
        try {
            redis.delete(key);
            recovered();
        } catch (RuntimeException ex) { unavailable(ex); }
    }

    public Optional<Long> increment(String key, Duration ttl) {
        if (!enabled) return Optional.empty();
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, ttl);
            recovered();
            return Optional.ofNullable(count);
        } catch (RuntimeException ex) {
            unavailable(ex);
            return Optional.empty();
        }
    }

    public boolean available() {
        if (!enabled) return false;
        try (var connection = redis.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            recovered();
            return "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException ex) {
            unavailable(ex);
            return false;
        }
    }

    private void unavailable(RuntimeException ex) {
        if (warningLogged.compareAndSet(false, true)) {
            log.warn("Redis 暂不可用，已自动切换到本机缓存；答卷仍写入数据库。原因：{}", ex.getClass().getSimpleName());
        }
    }

    private void recovered() {
        if (warningLogged.compareAndSet(true, false)) log.info("Redis 连接已恢复");
    }
}
