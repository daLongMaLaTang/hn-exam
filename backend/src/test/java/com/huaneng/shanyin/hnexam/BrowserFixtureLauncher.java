package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.ExamService;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import java.time.Instant;

/** Manual browser QA launcher; test classpath only, forcibly isolated from configuration and disk databases. */
public class BrowserFixtureLauncher {
    public static void main(String[] ignored) {
        var app = new SpringApplication(HnExamApplication.class);
        var context = app.run("--spring.config.import=", "--spring.datasource.url=jdbc:h2:mem:browser_qa;DB_CLOSE_DELAY=-1", "--spring.jpa.hibernate.ddl-auto=create-drop", "--server.port=18080", "--app.redis.enabled=false", "--app.bootstrap.question-bank-path=", "--app.ai.api-key=", "--app.web.allowed-origins=http://127.0.0.1:15173");
        var users=context.getBean(UserRepository.class); var roles=context.getBean(RoleRepository.class); var encoder=context.getBean(PasswordEncoder.class);var questions=context.getBean(QuestionRepository.class);var exams=context.getBean(ExamService.class);
        var fixtureAdmin=users.findByEmployeeNo("admin").orElseThrow();fixtureAdmin.setPasswordHash(encoder.encode("QaOnly@20261007"));fixtureAdmin.setMustChangePassword(false);users.save(fixtureAdmin);
        for(String number:List.of("qa-admin","qa-student","qa-other")){
            var u=new AppUser();u.setEmployeeNo(number);u.setName(number.equals("qa-admin")?"隔离验证管理员":"隔离验证学员");u.setPasswordHash(encoder.encode("QaOnly@20261007"));u.setMustChangePassword(false);u.getRoles().add(roles.findByCode(number.equals("qa-admin")?"SYSTEM_ADMIN":"CANDIDATE").orElseThrow());users.save(u);
        }
        var q=new Question();q.setType(QuestionType.SINGLE_CHOICE);q.setStem("开始检修前，应首先完成哪项工作？");q.setOptionsJson("[\"确认安全措施并办理工作许可\",\"立即开始检修\"]");q.setAnswer("A");q.setCourseCode("隔离验证课程");q.setStatus(QuestionStatus.ACTIVE);q.setExplanation("先落实安全措施，再开始工作。");questions.save(q);
        var s=new Question();s.setType(QuestionType.SHORT_ANSWER);s.setStem("请说明停电检修前应采取的安全措施。");s.setAnswer("停电、验电、装设接地线、悬挂标示牌和装设遮栏。");s.setCourseCode("隔离验证课程");s.setStatus(QuestionStatus.ACTIVE);questions.save(s);
        for(String title:List.of("手机答题与两次切屏验证","人工判卷流程验证")){
            var request=new ExamDtos.SaveRequest(title,"隔离验证课程",List.of(),"仅供隔离环境验证",30,100,60,null,null,false,1,false,true,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),40,1),new ExamDtos.QuestionItem(s.getId(),60,2)));
            Long id=exams.create(request).id();exams.publish(id);
            if(title.startsWith("人工")){var a=exams.start(id,"qa-student");exams.submit(a.attemptId(),"qa-student",new ExamDtos.SubmitRequest(Map.of(q.getId(),"A",s.getId(),"停电验电并接地"),0));}
        }
        var scheduled=new ExamDtos.SaveRequest("定时开放验证","隔离验证课程",List.of(),"到时可进入",30,100,60,Instant.now().plusSeconds(120),Instant.now().plusSeconds(3600),false,1,false,true,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),100,1)));
        exams.publish(exams.create(scheduled).id());
        System.out.println("BROWSER_QA_READY (isolated in-memory database, port 18080)");
    }
}
