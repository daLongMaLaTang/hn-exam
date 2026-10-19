package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.ExamService;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Disposable browser fixtures. Never imports local production configuration or uses a disk database. */
public class TrackingBrowserFixture {
    public static void main(String[] ignored) {
        var context = new SpringApplication(HnExamApplication.class).run("--spring.config.import=", "--spring.datasource.url=jdbc:h2:mem:tracking_browser;DB_CLOSE_DELAY=-1",
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--server.port=18080", "--app.redis.enabled=false", "--app.bootstrap.question-bank-path=", "--app.ai.api-key=",
                "--app.web.allowed-origins=http://127.0.0.1:15173", "--app.exams.expiry-check-ms=3600000");
        var users=context.getBean(UserRepository.class); var roles=context.getBean(RoleRepository.class); var encoder=context.getBean(PasswordEncoder.class);
        var service=context.getBean(ExamService.class); var exams=context.getBean(ExamRepository.class); var attempts=context.getBean(ExamAttemptRepository.class);
        var tx=context.getBean(TransactionTemplate.class); var password=encoder.encode("QaOnly@20261007");
        var admin=users.findByEmployeeNo("admin").orElseThrow(); admin.setPasswordHash(password); admin.setMustChangePassword(false); admin.setName("隔离验证管理员"); users.save(admin);
        List<AppUser> learners=new ArrayList<>();
        for(int i=0;i<24;i++) { var u=new AppUser();u.setEmployeeNo(i==0?"qa-student":"qa-student-"+i);u.setName(i==0?"陈晓（验证学员）":"验证学员"+i);u.setDepartment(i%2==0?"检修培训部":"运行培训部");u.setProfessionalDirection(i%2==0?"电气":"热控");u.setPasswordHash(password);u.setMustChangePassword(false);u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());learners.add(users.save(u)); }
        var q=new Question();q.setType(QuestionType.SINGLE_CHOICE);q.setStem("检修前首先需要确认什么？");q.setOptionsJson("[\"安全措施\",\"开始工作\"]");q.setAnswer("A");q.setCourseCode("安全培训");q.setStatus(QuestionStatus.ACTIVE);context.getBean(QuestionRepository.class).save(q);
        for(int n=0;n<3;n++) {
            String title=n==0?"秋季安全培训考核 · 应考人员进度验证":n==1?"历史考试名单核对验证":"长标题验证：秋季设备检修与运行安全专项培训阶段考核及专业技能提升综合考试安排";
            var request=new ExamDtos.SaveRequest(title,"安全培训",List.of(),"隔离验证数据",30,100,60,null,Instant.now().plusSeconds(86400),false,1,false,false,false,20,List.of(new ExamDtos.QuestionItem(q.getId(),100,1)));
            Long id=service.create(request).id();service.publish(id);
            if(n==0) {
                tx.executeWithoutResult(s -> { var e=exams.findById(id).orElseThrow(); for(int i=1;i<5;i++) { var a=new ExamAttempt();a.setExam(e);a.setUser(learners.get(i));a.setStartedAt(Instant.now().minusSeconds(300));a.setDeadline(Instant.now().plusSeconds(1800));a.setStatus(i==1?AttemptStatus.IN_PROGRESS:i==2?AttemptStatus.SUBMITTED:AttemptStatus.GRADED);if(i>=3){a.setScore(i==3?85:45);a.setPassed(i==3);a.setSubmittedAt(Instant.now());}attempts.save(a); } });
            }
            if(n==1) tx.executeWithoutResult(s -> {var e=exams.findById(id).orElseThrow();e.setAudienceFrozen(false);e.setStatus(ExamStatus.CLOSED);});
        }
        System.out.println("TRACKING_BROWSER_READY: isolated memory database, port 18080");
    }
}
