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
                private final Set<String> failedOnce=java.util.concurrent.ConcurrentHashMap.newKeySet();
                @Override public boolean available(){return true;}
                @Override public Structured structured(String system,Object input,Long userId) {
                    var data=json.valueToTree(input);
                    if(!data.has("candidateAnswer"))return new Structured(json.valueToTree(new Rubric(List.of(new Point("p1","包含参加人员","参加人员",1,"CONCEPT","完整表达本点，接受同义表述",false,false),new Point("p2","包含培训时间","培训时间",1,"CONCEPT","完整表达本点，接受同义表述",false,false),new Point("p3","包含培训内容","培训内容",1,"CONCEPT","完整表达本点，接受同义表述",false,false)),"隔离界面验证用评分草稿",FORMAT,POLICY,List.of())),"QA_SYNTHETIC");
                    try {
                        String answer=data.path("candidateAnswer").asText();
                        if(answer.contains("模拟失败一次") && failedOnce.add(answer)) throw new IllegalStateException("隔离测试首次调用失败");
                        var rubric=json.treeToValue(data.path("rubric"),Rubric.class);
                        var findings=rubric.points().stream().map(p->{String evidence=p.source();boolean matched=answer.contains(evidence);return new Finding(p.key(),answer.contains("模拟疑难")?"REVIEW":matched?"FULL":"NONE",matched?evidence:"",matched?"测试响应：作答包含该项":"测试响应：作答未包含该项");}).toList();
                        return new Structured(json.valueToTree(new Decision(FORMAT,POLICY,findings,answer.contains("模拟疑难")?List.of("测试响应：限定条件需要人工核对"):List.of())),"QA_SYNTHETIC");
                    }catch(Exception e){throw new IllegalStateException(e);}
                }
            };
        }
    }
    public static void main(String[] args) {
        var context=new SpringApplication(HnExamApplication.class,QaConfig.class).run("--spring.config.import=","--spring.datasource.url=jdbc:h2:mem:short_browser;DB_CLOSE_DELAY=-1","--spring.jpa.hibernate.ddl-auto=create-drop","--server.port=18081","--app.ai.short-automation-enabled=true","--app.redis.enabled=false","--app.bootstrap.question-bank-path=","--app.ai.api-key=","--app.web.allowed-origins=http://127.0.0.1:15174");
        var users=context.getBean(UserRepository.class);var roles=context.getBean(RoleRepository.class);var encoder=context.getBean(PasswordEncoder.class);
        var admin=users.findByEmployeeNo("admin").orElseThrow();admin.setPasswordHash(encoder.encode("QaOnly@20261007"));admin.setMustChangePassword(false);users.save(admin);
        var questions=context.getBean(QuestionRepository.class);var exams=context.getBean(ExamService.class);
        var q=new Question();q.setType(QuestionType.SHORT_ANSWER);q.setStem("【隔离验证】培训记录应包含哪些信息？");q.setAnswer("参加人员；培训时间；培训内容");q.setCourseCode("隔离验证");q.setStatus(QuestionStatus.ACTIVE);questions.save(q);
        var second=new Question();second.setType(QuestionType.SHORT_ANSWER);second.setStem("【隔离验证】请说明培训记录的三个基本项目，以及应当记录的内容，供逐项复核使用。");second.setAnswer(q.getAnswer());second.setCourseCode("隔离验证");second.setStatus(QuestionStatus.ACTIVE);questions.save(second);
        var standards=context.getBean(ShortAnswerRubricService.class);
        for(var question:List.of(second)) {
            var v=standards.view(question.getId());
            v=standards.save(question.getId(),new ShortAnswerRubricService.Save(v.version(),v.id(),new Rubric(List.of(new Point("p1","包含参加人员","参加人员",1,"CONCEPT","完整表达本点，接受同义表述",false,false),new Point("p2","包含培训时间","培训时间",1,"CONCEPT","完整表达本点，接受同义表述",false,false),new Point("p3","包含培训内容","培训内容",1,"CONCEPT","完整表达本点，接受同义表述",false,false)),"隔离测试标准",FORMAT,POLICY,List.of())),"admin");
            standards.confirm(question.getId(),new ShortAnswerRubricService.Expected(v.version(),v.id()),"admin");
        }
        var learners = new ArrayList<AppUser>();
        for(int i=1;i<=12;i++) {
            var u=new AppUser();u.setEmployeeNo("qa-short-"+i);u.setName("验证学员"+i);u.setPasswordHash(encoder.encode("QaOnly@20261007"));u.setMustChangePassword(false);u.getRoles().add(roles.findByCode("CANDIDATE").orElseThrow());users.save(u);
            learners.add(u);
        }
        Long eid=exams.create(new ExamDtos.SaveRequest("简答逐点阅卷验证","隔离验证",List.of(),"虚拟数据与模拟AI响应，不连接正式数据库",30,24,14,null,null,false,1,false,false,false,10,List.of(new ExamDtos.QuestionItem(q.getId(),12,1),new ExamDtos.QuestionItem(second.getId(),12,2)))).id();exams.publish(eid,"admin");
        for(int i=1;i<=learners.size();i++) {
            var u=learners.get(i-1);
            String text=i==1?"":i==2?" \t\u00a0":i==3?"参加人员。模拟疑难":i==4?"参加人员、培训时间、培训内容。模拟失败一次":i==5?"参加人员、时间与内容。概括表述":"参加人员、培训时间、培训内容。记录编号"+i;
            var a=exams.start(eid,u.getEmployeeNo());exams.submit(a.attemptId(),u.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(q.getId(),text,second.getId(),i==1?"":"参加人员、培训时间、培训内容。第二题编号"+i),0));
        }
        var blank=new Question();blank.setType(QuestionType.FILL_BLANK);blank.setStem("【隔离验证】三类设备包括___、___、___。");blank.setAnswer("甲;乙;丙");blank.setCourseCode("隔离验证");blank.setStatus(QuestionStatus.ACTIVE);questions.save(blank);
        Long blankExam=exams.create(new ExamDtos.SaveRequest("连续填空与翻页验证","隔离验证",List.of(),"仅隔离测试数据",30,12,7,null,null,false,1,false,false,false,10,List.of(new ExamDtos.QuestionItem(blank.getId(),12,1)))).id();exams.publish(blankExam);
        for(int i=0;i<learners.size();i++) {
            var u=learners.get(i);var a=exams.start(blankExam,u.getEmployeeNo());
            var answers=i<2?(i==0?List.of("丙","乙","甲"):List.of("乙","甲","丙")):
                    (i<4?(i==2?List.of("新甲","新乙","丙"):List.of("丙","新乙","新甲")):List.of("甲设备"+i,"乙设备"+i,"丙设备"+i));
            try {exams.submit(a.attemptId(),u.getEmployeeNo(),new ExamDtos.SubmitRequest(Map.of(blank.getId(),context.getBean(ObjectMapper.class).writeValueAsString(answers)),0));}catch(Exception e){throw new IllegalStateException(e);}
        }
        System.out.println("SHORT_QA_READY exam="+eid+" question="+q.getId());
    }
}
