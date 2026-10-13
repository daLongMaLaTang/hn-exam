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
    @Autowired AiUsageService usage;
    @Autowired com.huaneng.shanyin.hnexam.repository.AiUsageBucketRepository usageBuckets;
    @Autowired com.huaneng.shanyin.hnexam.repository.AiCallLeaseRepository leases;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean DeepSeekConnection connection;
    @BeforeEach void resetSettings() {
        settings.deleteAll(); leases.deleteAll(); usageBuckets.deleteAll(); usage.initialize();
    }
    @AfterAll static void clean() throws Exception { Files.deleteIfExists(keyFile); Files.deleteIfExists(keyFile.getParent()); }
    String body(String key) throws Exception { return json.writeValueAsString(Map.of("apiKey",key)); }
    @Test @WithMockUser(username="admin",authorities="SYSTEM_ADMIN")
    void adminCanConnectReplaceAndDisableWithoutReturningSecrets() throws Exception {
        mvc.perform(get("/api/ai/usage")).andExpect(status().isOk())
            .andExpect(jsonPath("limits.maxConcurrent").value(8)).andExpect(jsonPath("remainingCalls").value(20000));
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
    @Test void realAdmissionReleasesDatabaseBeforeHttpAndPersistsReportedTokens() throws Exception {
        var c=new com.huaneng.shanyin.hnexam.entity.AiSettings();c.setEnabled(true);
        c.setEndpoint(DeepSeekConnection.ENDPOINT);c.setModel(DeepSeekConnection.MODEL);
        c.setApiKeyCiphertext(cipher.encrypt("isolated-not-a-real-key"));settings.save(c);
        var client=mock(java.net.http.HttpClient.class);
        Object original=org.springframework.test.util.ReflectionTestUtils.getField(provider,"http");
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"http",client);
        try {
            @SuppressWarnings("unchecked") java.net.http.HttpResponse<String> response=mock(java.net.http.HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":20},\"choices\":[{\"message\":{\"content\":\"{\\\"points\\\":1,\\\"semanticSimilarity\\\":100,\\\"reason\\\":\\\"模拟结果\\\"}\"}}]}");
            when(client.send(any(java.net.http.HttpRequest.class),org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(call->{
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
                    assertThat(usage.today()).containsEntry("active",1L);
                    return response;
                });
            provider.suggest(new SubjectiveGradingProvider.Request(null,1L,com.huaneng.shanyin.hnexam.domain.QuestionType.FILL_BLANK,"题","答案","作答",1,123L));
            assertThat(usage.today()).containsEntry("active",0L).containsEntry("admitted",1L)
                .containsEntry("succeeded",1L).containsEntry("promptTokens",120L).containsEntry("completionTokens",20L);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(provider,"http",original); }
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
        mvc.perform(get("/api/ai/usage")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/settings")).andExpect(status().isForbidden());
        mvc.perform(put("/api/ai/settings").contentType("application/json").content(body("sk-test-secret"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/settings/disable")).andExpect(status().isForbidden());
    }
}
