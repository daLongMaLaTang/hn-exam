package com.huaneng.shanyin.hnexam.controller;
import com.huaneng.shanyin.hnexam.domain.*;import com.huaneng.shanyin.hnexam.entity.*;import com.huaneng.shanyin.hnexam.dto.UserSummary;import com.huaneng.shanyin.hnexam.repository.*;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import lombok.RequiredArgsConstructor;import org.springframework.transaction.annotation.Transactional;import org.springframework.web.bind.annotation.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;
import java.time.*;import java.util.*;
@RestController @RequestMapping("/api/profiles") @RequiredArgsConstructor @Transactional @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','TRAINING_ADMIN','DEPARTMENT_LEADER')")
public class ProfileController {
    private final com.huaneng.shanyin.hnexam.service.PrivateAdministratorPolicy privateAdmins;
    private final UserRepository users;private final ExamAttemptRepository attempts;private final ExternalGradeRepository external;private final PracticeDayRepository days;
    public record Fields(@Size(max=100) String professionalDirection,@Size(max=100) String educationMajor,@Size(max=100) String position,LocalDate trainingStartDate) {}
    public record Manual(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=100) String course,@Min(0) int score,@Min(1) int totalScore,@Min(0) int passScore,@NotNull LocalDate examDate,@NotBlank @Size(max=1000) String note,@NotBlank @Size(max=64) String requestId) {}
    public record Entry(String source,Long id,String title,String course,LocalDate date,Integer score,int totalScore,Boolean passed,String status,String recordedBy,Instant recordedAt,String note) {}
    public record Month(String month,long count,double averagePercent,double passRate,long practiceSeconds) {}
    public record Profile(UserSummary person,List<Entry> grades,List<Month> months,long practiceSeconds) {}
    @GetMapping("/{id}") public Profile get(@PathVariable Long id) {
        AppUser u=users.findById(id).orElseThrow(()->new IllegalArgumentException("人员不存在"));privateAdmins.requireVisible(u);
        List<Entry> entries=new ArrayList<>();
        attempts.findByUserIdOrderByStartedAtDesc(id).forEach(a->{var e=a.getExam();entries.add(new Entry("SYSTEM",a.getId(),e.getTitle(),e.getCourseCode(),(a.getSubmittedAt()==null?a.getStartedAt():a.getSubmittedAt()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),a.getScore(),e.getTotalScore(),a.getPassed(),e.getStatus()==ExamStatus.CANCELLED?"CANCELLED":a.getStatus().name(),a.getGradedByEmployeeNo(),a.getGradedAt(),a.getGradingNote()));});
        external.findByUserIdOrderByExamDateDesc(id).forEach(g->entries.add(entry(g)));
        entries.sort(Comparator.comparing(Entry::date).reversed());
        var time=days.findByUserIdOrderByDateDesc(id);Set<String> months=new TreeSet<>(Comparator.reverseOrder());entries.forEach(g->months.add(YearMonth.from(g.date()).toString()));time.forEach(d->months.add(YearMonth.from(d.getDate()).toString()));
        List<Month> analysis=months.stream().map(m->{var graded=entries.stream().filter(g->g.status().equals("GRADED")&&YearMonth.from(g.date()).toString().equals(m)).toList();long seconds=time.stream().filter(d->YearMonth.from(d.getDate()).toString().equals(m)).mapToLong(PracticeDay::getSeconds).sum();return new Month(m,graded.size(),graded.stream().mapToDouble(g->g.score()*100.0/g.totalScore()).average().orElse(0),graded.isEmpty()?0:100.0*graded.stream().filter(g->Boolean.TRUE.equals(g.passed())).count()/graded.size(),seconds);}).toList();
        return new Profile(UserSummary.from(u),entries,analysis,time.stream().mapToLong(PracticeDay::getSeconds).sum());
    }
    @PutMapping("/{id}") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','TRAINING_ADMIN')") public Profile update(@PathVariable Long id,@Valid @RequestBody Fields r) {var u=users.findById(id).orElseThrow(()->new IllegalArgumentException("人员不存在"));privateAdmins.requireVisible(u);u.setProfessionalDirection(r.professionalDirection());u.setEducationMajor(r.educationMajor());u.setPosition(r.position());u.setTrainingStartDate(r.trainingStartDate());return get(id);}
    @PostMapping("/{id}/manual-grades") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','TRAINING_ADMIN')") public Entry add(@PathVariable Long id,@Valid @RequestBody Manual r,@AuthenticationPrincipal Jwt jwt) {
        var u=users.findById(id).orElseThrow(()->new IllegalArgumentException("人员不存在"));privateAdmins.requireVisible(u);users.findLockedByEmployeeNo(u.getEmployeeNo()).orElseThrow();
        var existing=external.findByRequestId(r.requestId());if(existing.isPresent()){if(!existing.get().getUser().getId().equals(id))throw new IllegalArgumentException("请求编号重复");return entry(existing.get());}
        if(r.score()>r.totalScore() || r.passScore()>r.totalScore())throw new IllegalArgumentException("得分和及格分不能超过总分");
        var g=new ExternalGrade();g.setUser(u);g.setTitle(r.title());g.setCourse(r.course());g.setScore(r.score());g.setTotalScore(r.totalScore());g.setPassScore(r.passScore());g.setExamDate(r.examDate());g.setNote(r.note());g.setRecordedBy(privateAdmins.actorLabel(jwt.getSubject()));g.setRecordedAt(Instant.now());g.setRequestId(r.requestId());return entry(external.save(g));
    }
    private Entry entry(ExternalGrade g){return new Entry("MANUAL_RETAKE",g.getId(),g.getTitle(),g.getCourse(),g.getExamDate(),g.getScore(),g.getTotalScore(),g.getScore()>=g.getPassScore(),"GRADED",g.getRecordedBy(),g.getRecordedAt(),g.getNote());}
}
