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
    @Autowired ExamService examService;
    @Autowired QuestionRepository questions;
    @Test void excelExamCanBePublishedGradedAndExportedOnlyByPrivateAdmin() throws Exception {
        byte[] bytes;
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet("题库导入");
            String[][] rows = {{"课程","题型","题干","选项A","选项B","答案"},
                    {"导入验证","单选题","快照题干<script>","正确选项","错误选项","A"},
                    {"其他课程","判断题","错误课程","","","正确"}};
            for (int i=0;i<rows.length;i++) { var row=sheet.createRow(i); for(int j=0;j<rows[i].length;j++) row.createCell(j).setCellValue(rows[i][j]); }
            book.write(output); bytes=output.toByteArray();
        }
        var file = new org.springframework.mock.web.MockMultipartFile("file","exam.xlsx","application/octet-stream",bytes);
        var response=mvc.perform(multipart("/api/exams/import-excel").file(file).param("courseCode","导入验证").with(as(normal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(1)).andExpect(jsonPath("$.failed").value(1)).andReturn();
        long questionId=json.readTree(response.getResponse().getContentAsByteArray()).get("questionIds").get(0).asLong();
        var request=new com.huaneng.shanyin.hnexam.dto.ExamDtos.SaveRequest("导入考试验证","导入验证",List.of(),"",60,100,60,null,null,false,1,false,false,false,20,
                List.of(new com.huaneng.shanyin.hnexam.dto.ExamDtos.QuestionItem(questionId,100,1)));
        long examId=examService.create(request).id();
        var candidate=create("student_"+UUID.randomUUID().toString().substring(0,8),false);
        candidate.getRoles().clear(); candidate.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow()); users.save(candidate);
        examService.publish(examId,hidden.getEmployeeNo());
        var attempt=examService.start(examId,candidate.getEmployeeNo());
        var grade=examService.submitAcknowledged(attempt.attemptId(),candidate.getEmployeeNo(),new com.huaneng.shanyin.hnexam.dto.ExamDtos.SubmitRequest(Map.of(questionId,"正确选项"),0),java.time.Instant.now());
        assertThat(grade.grade().score()).isEqualTo(100);
        var q=questions.findById(questionId).orElseThrow(); q.setAnswer("修改后的答案"); questions.save(q);
        mvc.perform(get("/api/private-exams/html").with(as(normal))).andExpect(status().isForbidden());
        mvc.perform(get("/api/private-exams/html").with(jwt().jwt(j->j.subject(candidate.getEmployeeNo())).authorities(new SimpleGrantedAuthority("CANDIDATE")))).andExpect(status().isForbidden());
        long auditCount = audit.count();
        String html=mvc.perform(get("/api/private-exams/html").param("examId",String.valueOf(examId)).with(as(hidden)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(audit.count()).isEqualTo(auditCount);
        assertThat(html).contains("正确选项","快照题干&lt;script&gt;").doesNotContain("修改后的答案","<script>");
    }
    @Autowired ExamRepository examRepository;
    @Autowired PersonalExamPaperRepository paperRepository;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Test void archiveIncludesPersonalSnapshotsAttachmentsAndClosedExamsButExcludesDrafts() throws Exception {
        long id=transaction.execute(status -> {
            var q=new Question(); q.setType(com.huaneng.shanyin.hnexam.domain.QuestionType.CALCULATION);
            q.setStem("附件题"); q.setAnswer("参考步骤"); q.setCourseCode("验证");
            q.setOptionsJson("{\"paper\":\"data:application/pdf;base64,JVBERi0=\"}"); questions.save(q);
            var exam=new Exam(); exam.setTitle("个人历史试卷"); exam.setWrongQuestionPaper(true);
            exam.setStatus(com.huaneng.shanyin.hnexam.domain.ExamStatus.CLOSED); examRepository.save(exam);
            var paper=new PersonalExamPaper(); paper.setExam(exam); paper.setUser(normal);
            var item=new PersonalExamQuestion(); item.setPaper(paper); item.setQuestion(q); item.setPoints(100); item.setPosition(1); item.captureSnapshot();
            paper.getQuestions().add(item); paperRepository.save(paper);
            var draft=new Exam(); draft.setTitle("不应导出的草稿"); examRepository.save(draft);
            return exam.getId();
        });
        String html=mvc.perform(get("/api/private-exams/html").with(as(hidden))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("个人历史试卷","参考步骤","data:application/pdf;base64,JVBERi0=",normal.getEmployeeNo())
                .doesNotContain("不应导出的草稿");
    }
    @Test void loginUsesNormalPasswordAndOwnerCapability() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("employeeNo",hidden.getEmployeeNo(),"password","TestingOnly123!"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canManageSystem").value(true));
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("employeeNo",hidden.getEmployeeNo(),"password","wrong"))))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").with(as(hidden))).andExpect(jsonPath("$.canManageSystem").value(true));
        mvc.perform(get("/api/auth/me").with(as(normal))).andExpect(jsonPath("$.canManageSystem").value(false));
    }
    @Test void recoveryIsPrivateAndRequiresOneAdminDecision() throws Exception {
        var u=new AppUser(); u.setEmployeeNo("recover"+UUID.randomUUID().toString().substring(0,8)); u.setName("学员");
        u.setPasswordHash(encoder.encode("before")); u.setMustChangePassword(false);
        u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow()); users.save(u);
        String base="/api/users/password-requests";
        String body=json.writeValueAsString(Map.of("employeeNo",u.getEmployeeNo(),"name",u.getName()));
        for (int cycle=0;cycle<2;cycle++) {
            for(int i=0;i<2;i++) mvc.perform(post("/api/auth/password-recovery").contentType("application/json").content(body)).andExpect(status().isOk());
            var list=json.readTree(mvc.perform(get(base).with(as(normal))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertThat(list.size()).isEqualTo(1);
            String url=base+"/"+list.get(0).get("id").asLong()+"/"+(cycle==0?"reject":"approve");
            mvc.perform(post(url).with(jwt().jwt(j->j.subject(u.getEmployeeNo())).authorities(new SimpleGrantedAuthority("CANDIDATE")))).andExpect(status().isForbidden());
            mvc.perform(post(url).with(as(normal))).andExpect(status().isOk());
            mvc.perform(post(url).with(as(normal))).andExpect(status().isConflict());
            var updated=users.findById(u.getId()).orElseThrow();
            assertThat(updated.getAuthVersion()).isEqualTo(cycle);
            assertThat(updated.isMustChangePassword()).isEqualTo(cycle==1);
            assertThat(encoder.matches("before",updated.getPasswordHash())).isEqualTo(cycle==0);
        }
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
