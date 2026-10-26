package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.ExamService;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;

/** Test classpath only. No production configuration, persistent database or external AI. */
public class AnswerSyncBrowserFixture {
    public static void main(String[] args) {
        var context = new SpringApplication(HnExamApplication.class).run(
                "--spring.config.import=", "--spring.profiles.active=", "--spring.datasource.url=jdbc:h2:mem:answer_browser;DB_CLOSE_DELAY=-1",
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--server.port=18080", "--app.redis.enabled=false",
                "--app.bootstrap.question-bank-path=", "--app.ai.api-key=", "--app.ai.short-automation-enabled=false",
                "--app.web.allowed-origins=http://127.0.0.1:15173");
        var users=context.getBean(UserRepository.class); var roles=context.getBean(RoleRepository.class);
        var encoder=context.getBean(PasswordEncoder.class); var questions=context.getBean(QuestionRepository.class);
        var exams=context.getBean(ExamService.class);
        var u=new AppUser();u.setEmployeeNo("qa-sync");u.setName("答案同步验证学员");
        u.setPasswordHash(encoder.encode("QaSyncOnly20261022!"));u.setMustChangePassword(false);
        u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());users.save(u);
        var q=new Question();q.setType(QuestionType.SHORT_ANSWER);q.setStem("请说明检修前应采取的安全措施。");
        q.setAnswer("停电、验电、接地。");q.setCourseCode("答案同步隔离验证");q.setStatus(QuestionStatus.ACTIVE);questions.save(q);
        for(String title:List.of("多页面答案核对验证","最后一秒自动交卷验证","保存响应丢失恢复验证","超时保留草稿验证","缓冲结束草稿保留验证")) {
            var request=new ExamDtos.SaveRequest(title,q.getCourseCode(),List.of(),"隔离测试，不含真实人员和成绩",30,10,6,
                    null,null,false,1,false,false,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),10,1)));
            exams.publish(exams.create(request).id());
        }
        var mappings=context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        try {
            mappings.registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/qa/attempts/{id}/deadline")
                    .methods(org.springframework.web.bind.annotation.RequestMethod.POST).options(mappings.getBuilderConfiguration()).build(),
                    new ClockControl(context.getBean(ExamAttemptRepository.class),context.getBean(org.springframework.transaction.support.TransactionTemplate.class)),
                    ClockControl.class.getMethod("deadline",Long.class,int.class,org.springframework.security.oauth2.jwt.Jwt.class));
        } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
        System.out.println("ANSWER_SYNC_QA_READY");
    }

    public record ClockControl(ExamAttemptRepository attempts, org.springframework.transaction.support.TransactionTemplate tx) {
        @org.springframework.web.bind.annotation.ResponseBody
        public Map<String,String> deadline(@org.springframework.web.bind.annotation.PathVariable("id") Long id,
                @org.springframework.web.bind.annotation.RequestParam("seconds") int seconds,
                @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
            tx.executeWithoutResult(s -> {
                var a=attempts.findLockedById(id).orElseThrow();
                if(!a.getUser().getEmployeeNo().equals(jwt.getSubject())) throw new IllegalArgumentException("Not this test user");
                a.setDeadline(java.time.Instant.now().plusSeconds(seconds));
            });
            return Map.of("status","updated");
        }
    }
}
