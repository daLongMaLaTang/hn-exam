package com.huaneng.shanyin.hnexam.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SecurityConfigTest {
    @Test void rejectsPublicOrShortKeysAndGeneratesDistinctLocalKeys() {
        var config = new SecurityConfig();
        assertThatThrownBy(() -> config.jwtSecretKey("hnExam-local-development-secret-change-before-release-2026"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.jwtSecretKey("missing-mysql-jwt-secret"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(config.jwtSecretKey("").getEncoded()).hasSize(32)
                .isNotEqualTo(config.jwtSecretKey("").getEncoded());
        org.springframework.test.util.ReflectionTestUtils.setField(config, "requireConfiguredJwt", true);
        assertThatThrownBy(() -> config.jwtSecretKey(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HNEXAM_JWT_SECRET");
    }
}
