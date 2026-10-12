package com.huaneng.shanyin.hnexam.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.UserStatus;
import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthStateService {
    public record AuthState(Long userId, String employeeNo, String name, boolean active,
                            boolean mustChangePassword, int authVersion, Set<String> roles) {}
    private record LocalEntry(AuthState state, long expiresAt) {}

    private final UserRepository users;
    private final RedisSupport redis;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, LocalEntry> local = new ConcurrentHashMap<>();

    @Value("${app.cache.auth-ttl-seconds:300}")
    private long ttlSeconds;

    public Optional<AuthState> find(String employeeNo) {
        if (employeeNo == null || employeeNo.isBlank()) return Optional.empty();
        LocalEntry localEntry = local.get(employeeNo);
        long now = System.currentTimeMillis();
        if (localEntry != null && localEntry.expiresAt() > now) return Optional.of(localEntry.state());
        local.remove(employeeNo, localEntry);

        String key = redis.key("auth:user:" + employeeNo);
        Optional<String> cached = redis.get(key);
        if (cached.isPresent()) {
            try {
                AuthState state = objectMapper.readValue(cached.get(), AuthState.class);
                cacheLocally(state, now);
                return Optional.of(state);
            } catch (JsonProcessingException ignored) { redis.delete(key); }
        }

        return users.findByEmployeeNo(employeeNo).map(user -> {
            AuthState state = from(user);
            cache(state, now);
            return state;
        });
    }

    public void evictAfterCommit(String... employeeNumbers) {
        Runnable action = () -> {
            for (String employeeNo : employeeNumbers) if (employeeNo != null && !employeeNo.isBlank()) evict(employeeNo);
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    public AuthState from(AppUser user) {
        return new AuthState(user.getId(), user.getEmployeeNo(), user.getName(), user.getStatus() == UserStatus.ACTIVE,
                user.isMustChangePassword(), user.getAuthVersion(), user.getRoles().stream()
                .map(role -> role.effectiveAuthority()).collect(Collectors.toUnmodifiableSet()));
    }

    private void cache(AuthState state, long now) {
        cacheLocally(state, now);
        try {
            redis.put(redis.key("auth:user:" + state.employeeNo()), objectMapper.writeValueAsString(state), Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException ignored) { /* The database remains the source of truth. */ }
    }

    private void cacheLocally(AuthState state, long now) {
        // A short local layer keeps authentication fast even when Redis is being restarted.
        long localTtlMillis = Math.min(ttlSeconds, 30) * 1000L;
        local.put(state.employeeNo(), new LocalEntry(state, now + localTtlMillis));
    }

    private void evict(String employeeNo) {
        local.remove(employeeNo);
        redis.delete(redis.key("auth:user:" + employeeNo));
    }
}
