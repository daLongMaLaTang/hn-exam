package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;

@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:private_admin;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "app.bootstrap.question-bank-path=", "app.redis.enabled=false"})
@AutoConfigureMockMvc
class PrivateAdministratorIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AuditEventRepository audit;
    @Autowired PasswordEncoder encoder;
    @Autowired PrivateAdministratorPolicy policy;
    @Autowired PrivateAdministratorProvisioner provisioner;
    @Autowired ObjectMapper json;
    AppUser hidden, normal;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @BeforeEach void setup() {
        hidden = create("private_"+UUID.randomUUID().toString().substring(0,8),true);
        normal = create("normal_"+UUID.randomUUID().toString().substring(0,8),false);
    }
    AppUser create(String name, boolean hidden) {
        var u = new AppUser();u.setEmployeeNo(name);u.setName("验证管理员");u.setPasswordHash(encoder.encode("TestingOnly123!"));
        u.setPrivateAdministrator(hidden);u.setMustChangePassword(false);u.getRoles().add(roles.findByCode("SYSTEM_ADMIN").orElseThrow());return users.save(u);
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(AppUser user) {
        return jwt().jwt(j -> j.subject(user.getEmployeeNo())).authorities(new SimpleGrantedAuthority("SYSTEM_ADMIN"));
    }
    @Test void loginUsesNormalPasswordAndOwnerCapability() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("employeeNo",hidden.getEmployeeNo(),"password","TestingOnly123!"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canManageSystem").value(true));
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("employeeNo",hidden.getEmployeeNo(),"password","wrong"))))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").with(as(hidden))).andExpect(jsonPath("$.canManageSystem").value(true));
        mvc.perform(get("/api/auth/me").with(as(normal))).andExpect(jsonPath("$.canManageSystem").value(false));
    }
    @Test void directoryAndDirectIdsHidePrivateAccounts() throws Exception {
        String list=mvc.perform(get("/api/users").with(as(normal))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(normal.getEmployeeNo()).doesNotContain(hidden.getEmployeeNo());
        String monthly=mvc.perform(get("/api/dashboard/monthly-learning").with(as(normal))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(monthly).doesNotContain(hidden.getEmployeeNo());
        long visible=users.findAll().stream().filter(policy::visible).count();
        mvc.perform(get("/api/dashboard/summary").with(as(normal))).andExpect(jsonPath("$.userCount").value((int)visible));
        mvc.perform(get("/api/profiles/"+hidden.getId()).with(as(normal))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/"+hidden.getId()).with(as(hidden)).contentType("application/json").content("{\"employeeNo\":\"not_exposed\",\"name\":\"改名\",\"status\":\"ACTIVE\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/"+hidden.getId()+"/reset-password").with(as(normal))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/"+hidden.getId()+"/roles").with(as(hidden)).contentType("application/json").content("{\"roles\":[\"CANDIDATE\"]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void ownerPermissionsAreNotGrantedToOtherSystemAdminsAndAuditUsesAlias() throws Exception {
        mvc.perform(get("/api/ai/settings").with(as(hidden))).andExpect(status().isOk());
        mvc.perform(get("/api/ai/settings").with(as(normal))).andExpect(status().isForbidden());
        String code="QA_"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        mvc.perform(post("/api/users/roles").with(as(hidden)).contentType("application/json").content(json.writeValueAsString(Map.of("code",code,"name","测试角色","permissionGroup","CANDIDATE"))))
            .andExpect(status().isOk());
        mvc.perform(post("/api/users/roles").with(as(normal)).contentType("application/json").content(json.writeValueAsString(Map.of("code",code+"X","name","测试角色","permissionGroup","CANDIDATE"))))
            .andExpect(status().isForbidden());
        assertThat(audit.findAll()).anyMatch(e -> "维护管理员".equals(e.getActor()) && e.getResponseStatus()==200)
            .noneMatch(e -> hidden.getEmployeeNo().equals(e.getActor()));
    }
    @Test void passwordChangeRequiredAndDisabledChecksStillApply() throws Exception {
        hidden.setMustChangePassword(true);users.save(hidden);
        mvc.perform(get("/api/users").with(as(hidden))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        hidden.setStatus(com.huaneng.shanyin.hnexam.domain.UserStatus.DISABLED);users.save(hidden);
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("employeeNo",hidden.getEmployeeNo(),"password","TestingOnly123!"))))
            .andExpect(status().isUnauthorized());
    }
    @Test void oneTimeProvisioningCreatesOwnerAndNeverResetsExistingPassword() throws Exception {
        var input=temporary.resolve("bootstrap.properties");
        String login="setup_"+UUID.randomUUID().toString().substring(0,8);
        String hash=encoder.encode("SetupPassword123!");
        java.nio.file.Files.writeString(input,"databaseUrl=jdbc:h2:mem:private_admin;DB_CLOSE_DELAY=-1\nlogin="+login+"\npasswordHash="+hash+"\n");
        org.springframework.test.util.ReflectionTestUtils.setField(provisioner,"bootstrapFile",input.toString());
        provisioner.provisionIfRequested();
        var created=users.findByEmployeeNo(login).orElseThrow();
        assertThat(created.isPrivateAdministrator()).isTrue();
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(policy.canManageSystem(created)).isTrue();
        assertThat(input).doesNotExist();
        java.nio.file.Files.writeString(input,"databaseUrl=jdbc:h2:mem:private_admin;DB_CLOSE_DELAY=-1\nlogin="+login+"\npasswordHash="+encoder.encode("DifferentPassword123!")+"\n");
        provisioner.provisionIfRequested();
        assertThat(users.findByEmployeeNo(login).orElseThrow().getPasswordHash()).isEqualTo(hash);
        assertThat(input).doesNotExist();
    }
    @Test void provisioningCannotTakeOverExistingUser() throws Exception {
        var input=temporary.resolve("occupied.properties");
        java.nio.file.Files.writeString(input,"databaseUrl=jdbc:h2:mem:private_admin;DB_CLOSE_DELAY=-1\nlogin="+normal.getEmployeeNo()+"\npasswordHash="+encoder.encode("SetupPassword123!")+"\n");
        org.springframework.test.util.ReflectionTestUtils.setField(provisioner,"bootstrapFile",input.toString());
        assertThatThrownBy(provisioner::provisionIfRequested).hasMessageContaining("已被占用");
        assertThat(users.findById(normal.getId()).orElseThrow().isPrivateAdministrator()).isFalse();
        assertThat(input).exists();
    }
    @Test void isolatedDatabaseDoesNotConsumeProductionBootstrap() throws Exception {
        var input=temporary.resolve("different-database.properties");
        java.nio.file.Files.writeString(input,"databaseUrl=jdbc:mysql://example.invalid/production\nlogin=unused_owner\npasswordHash=unused\n");
        org.springframework.test.util.ReflectionTestUtils.setField(provisioner,"bootstrapFile",input.toString());
        provisioner.provisionIfRequested();
        assertThat(input).exists();
        assertThat(users.findByEmployeeNo("unused_owner")).isEmpty();
    }
}
