package com.huaneng.shanyin.hnexam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.repository.AiSettingsRepository;
import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:ai_settings;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "app.redis.enabled=false", "app.bootstrap.question-bank-path=", "app.ai.api-key="})
@AutoConfigureMockMvc
class AiSettingsIntegrationTest {
    static final Path keyFile;
    static { try { keyFile=Files.createTempDirectory("hnexam-ai-test").resolve("master.key"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.ai.master-key-file",keyFile::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AiSettingsRepository settings;
    @Autowired AiKeyCipher cipher;
    @Autowired OpenAiCompatibleGradingProvider provider;
    @MockitoBean DeepSeekConnection connection;
    @BeforeEach void resetSettings() { settings.deleteAll(); }
    @AfterAll static void clean() throws Exception { Files.deleteIfExists(keyFile); Files.deleteIfExists(keyFile.getParent()); }
    String body(String key) throws Exception { return json.writeValueAsString(Map.of("apiKey",key)); }
    @Test @WithMockUser(username="admin",authorities="SYSTEM_ADMIN")
    void adminCanConnectReplaceAndDisableWithoutReturningSecrets() throws Exception {
        String key="sk-isolated-first-secret";
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body(key)))
            .andExpect(status().isOk()).andExpect(jsonPath("available").value(true))
            .andExpect(jsonPath("apiKey").doesNotExist()).andExpect(jsonPath("apiKeyCiphertext").doesNotExist());
        verify(connection).verify(key);
        var saved=settings.findById(1L).orElseThrow();
        assertThat(saved.getApiKeyCiphertext()).doesNotContain(key);
        assertThat(new AiKeyCipher(keyFile.toString()).decrypt(saved.getApiKeyCiphertext())).isEqualTo(key);
        assertThat(saved.getEndpoint()).isEqualTo(DeepSeekConnection.ENDPOINT);
        assertThat(saved.getModel()).isEqualTo(DeepSeekConnection.MODEL);
        assertThat(provider.available()).isTrue();
        mvc.perform(get("/api/ai/settings")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(key))));
        String original=saved.getApiKeyCiphertext();
        doThrow(new IllegalArgumentException("API Key无效")).when(connection).verify("sk-invalid-replacement");
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body("sk-invalid-replacement"))).andExpect(status().isBadRequest());
        assertThat(settings.findById(1L).orElseThrow().getApiKeyCiphertext()).isEqualTo(original);
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body("sk-isolated-second-secret"))).andExpect(status().isOk());
        assertThat(cipher.decrypt(settings.findById(1L).orElseThrow().getApiKeyCiphertext())).isEqualTo("sk-isolated-second-secret");
        mvc.perform(post("/api/ai/settings/disable")).andExpect(status().isOk()).andExpect(jsonPath("available").value(false));
        assertThat(provider.available()).isFalse();
    }
    @Test @WithMockUser(username="another-admin",authorities="SYSTEM_ADMIN")
    void OtherSystemAdminCanReadStatusButCannotAccessConfiguration() throws Exception {
        mvc.perform(get("/api/ai/status")).andExpect(status().isOk()).andExpect(jsonPath("keyConfigured").doesNotExist());
        denied(); verifyNoInteractions(connection); assertThat(settings.count()).isZero();
    }
    @Test @WithMockUser(username="admin",authorities="CANDIDATE")
    void NameAloneDoesNotGrantAccess() throws Exception { denied(); mvc.perform(get("/api/ai/status")).andExpect(status().isForbidden()); }
    @Test void anonymousCannotReadOrWrite() throws Exception {
        mvc.perform(get("/api/ai/settings")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body("sk-test-secret"))).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(username="admin",authorities="SYSTEM_ADMIN")
    void rejectsEmptyOrOversizedKeyBeforeVerification() throws Exception {
        for(String key:new String[]{"", "x".repeat(513)}) mvc.perform(put("/api/ai/settings").contentType("application/json").content(body(key))).andExpect(status().isBadRequest());
        verifyNoInteractions(connection);
    }
    void denied() throws Exception {
        mvc.perform(get("/api/ai/settings")).andExpect(status().isForbidden());
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body("sk-test-secret"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/settings/disable")).andExpect(status().isForbidden());
    }
}
