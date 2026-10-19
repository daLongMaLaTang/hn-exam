package com.huaneng.shanyin.hnexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.huaneng.shanyin.hnexam.service.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerScoring.*;

/** Test classpath only. All data and model responses are synthetic, no network requests. */
public class ShortAnswerBrowserFixture {
    @TestConfiguration
    public static class QaConfig {
        @Bean @Primary OpenAiCompatibleGradingProvider syntheticProvider(AiSettingsRepository settings,ObjectMapper json,AiKeyCipher cipher,AiUsageService usage) {
            return new OpenAiCompatibleGradingProvider(settings,json,"",cipher,usage) {
                @Override public boolean available(){return true;}
                @Override public Structured structured(String system,Object input,Long userId) {
                    var data=json.valueToTree(input);
                    if(!data.has("candidateAnswer"))return new Structured(json.valueToTree(new Rubric(List.of(new Point("p1","包含参加人员","参加人员",1,"CONCEPT","",false,false),new Point("p2","包含培训时间","培训时间",1,"CONCEPT","",false,false),new Point("p3","包含培训内容","培训内容",1,"CONCEPT","",false,false)),"隔离界面验证用评分草稿")),"QA_SYNTHETIC");
                    try {
                        var rubric=json.treeToValue(data.path("rubric"),Rubric.class);String answer=data.path("candidateAnswer").asText();
                        var findings=rubric.points().stream().map(p->{String evidence=p.source();boolean matched=answer.contains(evidence);return new Finding(p.key(),matched?"MET":"MISSING",matched?evidence:"",matched?"测试响应：作答包含该项":"测试响应：作答未包含该项");}).toList();
                        return new Structured(json.valueToTree(new Result(findings,90,List.of())),"QA_SYNTHETIC");
                    }catch(Exception e){throw new IllegalStateException(e);}
                }
            };
        }
    }
    public static void main(String[] args) {
        var context=new SpringApplication(HnExamApplication.class,QaConfig.class).run("--spring.config.import=","--spring.datasource.url=jdbc:h2:mem:short_browser;DB_CLOSE_DELAY=-1","--spring.jpa.hibernate.ddl-auto=create-drop","--server.port=18080","--app.redis.enabled=false","--app.bootstrap.question-bank-path=","--app.ai.api-key=","--app.web.allowed-origins=http://127.0.0.1:15173");
        var users=context.getBean(UserRepository.class);var roles=context.getBean(RoleRepository.class);var encoder=context.getBean(PasswordEncoder.class);
        var admin=users.findByEmployeeNo("admin").orElseThrow();admin.setPasswordHash(encoder.encode("QaOnly@20261007"));admin.setMustChangePassword(false);users.save(admin);
        var questions=context.getBean(QuestionRepository.class);var exams=context.getBean(ExamService.class);
        var q=new Question();q.setType(QuestionType.SHORT_ANSWER);q.setStem("【隔离验证】培训记录应包含哪些信息？");q.setAnswer("参加人员；培训时间；培训内容");q.setCourseCode("隔离验证");q.setStatus(QuestionStatus.ACTIVE);questions.save(q);
        var learners = new ArrayList<AppUser>();
        for(int i=1;i<=4;i++) {
            var u=new AppUser();u.setEmployeeNo("qa-short-"+i);u.setName("验证学员"+i);u.setPasswordHash(encoder.encode("QaOnly@20261007"));u.setMustChangePassword(false);u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());users.save(u);
            learners.add(u);
        }
        Long eid=exams.create(new ExamDtos.SaveRequest("简答逐点阅卷验证","隔离验证",List.of(),"虚拟数据与模拟AI响应，不连接正式数据库",30,12,7,null,null,false,1,false,false,false,10,List.of(new ExamDtos.QuestionItem(q.getId(),12,1)))).id();exams.publish(eid);
        for(int i=1;i<=learners.size();i++) {
            var u=learners.get(i-1);
            var a=exams.start(eid,u.getEmployeeNo());exams.submit(a.attemptId(),u.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(q.getId(),"参加人员、培训时间、培训内容。记录编号"+i),0));
        }
        System.out.println("SHORT_QA_READY exam="+eid+" question="+q.getId());
    }
}
