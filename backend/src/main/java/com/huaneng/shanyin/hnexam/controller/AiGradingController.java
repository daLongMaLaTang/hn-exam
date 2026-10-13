package com.huaneng.shanyin.hnexam.controller;
import com.huaneng.shanyin.hnexam.service.*;
import com.huaneng.shanyin.hnexam.repository.AiSettingsRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;

@RestController @RequiredArgsConstructor @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class AiGradingController {
    private final OpenAiCompatibleGradingProvider provider;
    private final AiSettingsRepository settings;
    private final AiGradingService grading;
    private final AiKeyCipher cipher;
    private final DeepSeekConnection connection;
    private final AiUsageService usage;
    public record Config(@NotBlank(message="请填写DeepSeek API Key") @Size(max=512, message="API Key长度不能超过512个字符") String apiKey) {
        @Override public String toString() { return "Config[apiKey=REDACTED]"; }
    }
    @GetMapping("/api/ai/status") public Map<String,Object> status() {
        return Map.of("available", provider.available());
    }
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN') and authentication.name == 'admin'")
    @GetMapping("/api/ai/usage") public Map<String,Object> usage() { return usage.today(); }
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN') and authentication.name == 'admin'")
    @GetMapping("/api/ai/settings") public Map<String,Object> get() {
        var c=provider.config();
        return Map.of("enabled", c.isEnabled(), "keyConfigured", provider.keyConfigured(), "available", provider.available());
    }
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN') and authentication.name == 'admin'")
    @PutMapping("/api/ai/settings") public Map<String,Object> save(@Valid @RequestBody Config r) {
        String key=r.apiKey().trim();
        connection.verify(key);
        String encrypted=cipher.encrypt(key);
        var c=provider.config();
        c.setEndpoint(DeepSeekConnection.ENDPOINT); c.setModel(DeepSeekConnection.MODEL);
        c.setTimeoutSeconds(60); c.setApiKeyCiphertext(encrypted); c.setEnabled(true);
        settings.save(c);
        return get();
    }
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN') and authentication.name == 'admin'")
    @PostMapping("/api/ai/settings/disable") public Map<String,Object> disable() {
        var c=provider.config(); c.setEnabled(false); settings.save(c); return get();
    }
    @PostMapping("/api/exams/attempts/{id}/ai/{questionId}")
    public Map<String,String> suggest(@PathVariable Long id,@PathVariable Long questionId) { return grading.suggest(id,questionId); }
}
