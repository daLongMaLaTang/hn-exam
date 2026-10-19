package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Exam-only blank grading. Practice continues to use its separate grading workflow. */
@Service @RequiredArgsConstructor
public class FillBlankReviewService {
    private final com.huaneng.shanyin.hnexam.service.PrivateAdministratorPolicy privateAdmins;
    private final FillBlankRuleRepository rules;
    private final FillBlankDecisionRepository decisions;
    private final FillBlankMarkRepository marks;
    private final ExamRepository exams;
    private final ExamAttemptRepository attempts;
    @PersistenceContext private EntityManager em;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Item(Long questionId, String stem, String reference, int points) {}
    public record Group(Long questionId, String version, int slot, String stem, String reference,
                        String submitted, int count, List<Long> answerIds) {}
    public record DecisionRequest(Long questionId, String version, int slot, String submitted,
                                  boolean correct, boolean remember, List<Long> answerIds) {}
    public record History(Long id, String stem, int slot, String submitted, boolean correct,
                          int count, Long ruleId, String actor, Instant at, Instant revokedAt) {}
    public record Usage(Long attemptId, Long examId, String examTitle, boolean published) {}
    public record RuleView(Long id, String stem, int slot, String acceptedAnswer, boolean active,
                           String actor, Instant at, List<Usage> usages) {}
    public record Workspace(List<Group> groups, int pendingSlots, int readyCount,
                            List<History> history, List<RuleView> rules) {}

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String version(Item item) {
        // Includes exact stem/reference and grading algorithm version. Never follows a changed live question.
        return hash("exam-blank-v1\0" + item.stem() + "\0" + item.reference());
    }
    public static List<String> referenceParts(String value) {
        if (value == null || value.isBlank()) return List.of();
        value = value.replaceFirst("[\\r\\n]+$", "");
        String separator = value.matches("(?s).*[|｜\\r\\n].*") ? "[|｜]|\\R" : "[,，、;；]";
        return Arrays.stream(value.split(separator, -1)).map(String::strip).toList();
    }
    public static List<String> submittedParts(String value) {
        if (value != null && value.stripLeading().startsWith("[")) {
            try {
                var array = JSON.readTree(value);
                if (array.isArray()) {
                    var result = new ArrayList<String>();
                    for (var element : array) {
                        if (!element.isTextual()) throw new IllegalArgumentException("填空作答格式错误");
                        result.add(element.textValue().strip());
                    }
                    return result;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* legacy free text */ }
        }
        return referenceParts(value);
    }

    /** Caller holds the attempt lock; only initialize new/unpublished scores. */
    public void initialize(Item item, AttemptAnswer answer) {
        if (!answer.getBlankMarks().isEmpty()) return;
        var reference = referenceParts(item.reference());
        if (reference.isEmpty()) throw new IllegalStateException("填空题缺少参考答案，请核对题库");
        var submitted = submittedParts(answer.getAnswerText());
        var known = rules.findByQuestionIdAndQuestionVersionAndActiveTrue(item.questionId(), version(item));
        boolean extra = submitted.size() > reference.size()
                && submitted.subList(reference.size(), submitted.size()).stream().anyMatch(s -> !s.isEmpty());
        for (int i=0; i<reference.size(); i++) {
            var mark = new FillBlankMark(); mark.setAnswer(answer); mark.setSlot(i+1);
            mark.setQuestionVersion(version(item));
            String text = i < submitted.size() ? submitted.get(i) : ""; mark.setSubmitted(text);
            mark.setSource("PENDING");
            if (extra || text.isEmpty()) { mark.setCorrect(false); mark.setSource(extra ? "EXTRA" : "EMPTY"); }
            else if (Arrays.stream(reference.get(i).split("~",-1)).map(String::strip).anyMatch(text::equals)) {
                mark.setCorrect(true); mark.setSource("STANDARD");
            } else {
                int slot=i+1;
                known.stream().filter(r -> r.getSlot()==slot && r.getAcceptedAnswer().equals(text)).findFirst().ifPresent(r -> {
                    mark.setCorrect(true); mark.setSource("RULE"); mark.setRuleId(r.getId());
                });
            }
            answer.getBlankMarks().add(mark);
        }
        recompute(item, answer);
    }

    public void recompute(Item item, AttemptAnswer answer) {
        var values = answer.getBlankMarks();
        long pending = values.stream().filter(m -> m.getCorrect()==null).count();
        long correct = values.stream().filter(m -> Boolean.TRUE.equals(m.getCorrect())).count();
        boolean manual = values.stream().anyMatch(m -> "MANUAL".equals(m.getSource()));
        answer.setAiStatus(pending > 0 ? "FILL_GROUP_PENDING" : "FILL_GROUP_READY");
        answer.setAiSuggestedPoints(null); answer.setAiSuggestionReason(null); answer.setAiModel(null);
        answer.setAwardedPoints(pending > 0 ? null : (int)Math.round((double)item.points()*correct/values.size()));
        answer.setCorrect(pending > 0 ? null : correct==values.size());
        answer.setGradingMethod(manual ? GradingMethod.MANUAL : GradingMethod.AUTO);
        answer.setGradingComment("共 " + values.size() + " 空，答对 " + correct + " 空" +
                (pending > 0 ? "，待集中判定 " + pending + " 空。" : "；按答对比例四舍五入计分，待统一公布。") +
                (values.stream().anyMatch(m -> "EXTRA".equals(m.getSource())) ? "作答数量超过空数，本题计0分。" : ""));
    }

    public static List<Item> items(ExamAttempt a) {
        if (a.getPersonalPaper()!=null) return a.getPersonalPaper().getQuestions().stream()
                .filter(q -> q.getSnapshotType()==QuestionType.FILL_BLANK)
                .map(q -> new Item(q.getQuestion().getId(),q.getSnapshotStem(),q.getSnapshotAnswer(),q.getPoints())).toList();
        return a.getExam().getQuestions().stream()
                .filter(q -> (q.getSnapshotType()==null ? q.getQuestion().getType() : q.getSnapshotType())==QuestionType.FILL_BLANK)
                .map(q -> new Item(q.getQuestion().getId(),q.getSnapshotStem()==null?q.getQuestion().getStem():q.getSnapshotStem(),
                        q.getSnapshotAnswer()==null?q.getQuestion().getAnswer():q.getSnapshotAnswer(),q.getPoints())).toList();
    }
    private AttemptAnswer answer(ExamAttempt a, Long questionId) {
        return a.getAnswers().stream().filter(v -> v.getQuestion().getId().equals(questionId)).findFirst().orElseThrow();
    }
    private Exam lockExam(Long id) {
        var exam=exams.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("考试不存在"));
        if (exam.getStatus()==ExamStatus.CANCELLED) throw new IllegalStateException("考试已取消，不能集中阅卷");
        return exam;
    }
    private ExamAttempt lockAttempt(Long id) {
        var a=attempts.findLockedById(id).orElseThrow(); em.refresh(a,LockModeType.PESSIMISTIC_WRITE); return a;
    }
    private List<ExamAttempt> prepare(Long examId) {
        var all=attempts.findByExamIdOrderByStartedAtDesc(examId).stream().sorted(Comparator.comparing(ExamAttempt::getId)).toList();
        for(var entry:all) {
            if(entry.getStatus()!=AttemptStatus.SUBMITTED) continue;
            var a=lockAttempt(entry.getId());
            if(a.getStatus()!=AttemptStatus.SUBMITTED) continue;
            for(var item:items(a)) {
                if(a.getAnswers().stream().noneMatch(v -> v.getQuestion().getId().equals(item.questionId()))) {
                    var missing=new AttemptAnswer();missing.setAttempt(a);missing.setQuestion(em.getReference(Question.class,item.questionId()));a.getAnswers().add(missing);
                }
                var value=answer(a,item.questionId());
                if(value.getBlankMarks().isEmpty() && value.getGradingMethod()!=GradingMethod.MANUAL) initialize(item,value);
            }
        }
        em.flush(); return all;
    }
    private List<Group> groups(List<ExamAttempt> all) {
        Map<String,List<Long>> ids=new LinkedHashMap<>(); Map<String,Group> templates=new LinkedHashMap<>();
        for(var a:all) {
            if(a.getStatus()!=AttemptStatus.SUBMITTED) continue;
            for(var item:items(a)) {
                var value=answer(a,item.questionId());
                for(var m:value.getBlankMarks()) {
                    if(m.getCorrect()!=null) continue;
                    String key=item.questionId()+":"+m.getQuestionVersion()+":"+m.getSlot()+":"+hash(m.getSubmitted());
                    ids.computeIfAbsent(key,k -> new ArrayList<>()).add(value.getId());
                    templates.putIfAbsent(key,new Group(item.questionId(),m.getQuestionVersion(),m.getSlot(),item.stem(),
                            referenceParts(item.reference()).get(m.getSlot()-1),m.getSubmitted(),0,List.of()));
                }
            }
        }
        return ids.entrySet().stream().map(e -> {var t=templates.get(e.getKey());
            return new Group(t.questionId(),t.version(),t.slot(),t.stem(),t.reference(),t.submitted(),e.getValue().size(),e.getValue().stream().sorted().toList());
        }).sorted(Comparator.comparing(Group::questionId).thenComparing(Group::slot).thenComparing(Comparator.comparingInt(Group::count).reversed())).toList();
    }

    @Transactional
    public Workspace workspace(Long examId) {
        lockExam(examId); var all=prepare(examId); var groups=groups(all);
        var questionIds=new HashSet<Long>();
        all.forEach(a -> items(a).forEach(i -> questionIds.add(i.questionId())));
        var history=decisions.findByExamIdOrderByIdDesc(examId).stream().map(d -> new History(d.getId(),d.getStem(),d.getSlot(),d.getSubmitted(),d.isCorrect(),d.getAffectedCount(),d.getRuleId(),d.getActor(),d.getCreatedAt(),d.getRevokedAt())).toList();
        var ruleViews=rules.findByQuestionIdInOrderByIdDesc(questionIds).stream().map(r -> new RuleView(r.getId(),r.getStem(),r.getSlot(),r.getAcceptedAnswer(),r.isActive(),r.getCreatedBy(),r.getCreatedAt(),usages(r.getId()))).toList();
        int ready=(int)all.stream().filter(a -> a.getStatus()==AttemptStatus.SUBMITTED && (!items(a).isEmpty() || ShortAnswerReviewService.centrallyGraded(a)) && ready(a)).count();
        return new Workspace(groups,groups.stream().mapToInt(Group::count).sum(),ready,history,ruleViews);
    }
    private List<Usage> usages(Long ruleId) {
        return marks.findByRuleId(ruleId).stream().map(m -> m.getAnswer().getAttempt()).distinct()
                .map(a -> new Usage(a.getId(),a.getExam().getId(),a.getExam().getTitle(),a.getStatus()==AttemptStatus.GRADED)).toList();
    }

    @Transactional
    public int decide(Long examId, DecisionRequest request, String actor) {
        if(request==null || request.questionId()==null || request.version()==null || request.submitted()==null
                || request.slot()<1 || request.answerIds()==null || request.answerIds().isEmpty() || request.answerIds().size()>10000
                || request.submitted().length()>10000 || (!request.correct() && request.remember())) throw new IllegalArgumentException("判定参数不正确");
        lockExam(examId); var all=prepare(examId);
        var group=groups(all).stream().filter(g -> g.questionId().equals(request.questionId()) && g.version().equals(request.version())
                && g.slot()==request.slot() && g.submitted().equals(request.submitted())).findFirst().orElseThrow(() -> new IllegalStateException("该答案组已处理，请刷新"));
        if(!new TreeSet<>(group.answerIds()).equals(new TreeSet<>(request.answerIds()))) throw new IllegalStateException("答案组人数已变化，请刷新后重新确认");
        var d=new FillBlankDecision(); d.setExamId(examId);d.setQuestionId(group.questionId());d.setQuestionVersion(group.version());d.setSlot(group.slot());
        d.setStem(group.stem());d.setSubmitted(group.submitted());d.setCorrect(request.correct());d.setAffectedCount(group.count());d.setActor(privateAdmins.actorLabel(actor));d.setCreatedAt(Instant.now());d.setTargetAnswerIds(group.answerIds().toString());
        if(request.remember()) {
            // Serialize rule creation across exams for this question, backed by the DB unique key.
            em.find(Question.class,group.questionId(),LockModeType.PESSIMISTIC_WRITE);
            var r=rules.findByQuestionIdAndQuestionVersionAndSlotAndAnswerHash(group.questionId(),group.version(),group.slot(),hash(group.submitted())).orElseGet(FillBlankRule::new);
            r.setQuestionId(group.questionId());r.setQuestionVersion(group.version());r.setSlot(group.slot());r.setAnswerHash(hash(group.submitted()));r.setAcceptedAnswer(group.submitted());r.setStem(group.stem());
            r.setActive(true);r.setCreatedBy(privateAdmins.actorLabel(actor));r.setCreatedAt(Instant.now());r.setDisabledAt(null);r.setDisabledBy(null);rules.saveAndFlush(r);d.setRuleId(r.getId());
        }
        decisions.saveAndFlush(d);
        var targets=new HashSet<>(group.answerIds());
        for(var a:all) {
          if(a.getStatus()!=AttemptStatus.SUBMITTED) continue;
          for(var item:items(a)) {
            var v=answer(a,item.questionId()); if(!targets.contains(v.getId())) continue;
            var m=v.getBlankMarks().stream().filter(x -> x.getSlot()==group.slot()).findFirst().orElseThrow();
            m.setCorrect(request.correct());m.setDecisionId(d.getId());m.setRuleId(d.getRuleId());m.setSource("MANUAL");recompute(item,v);
          }
        }
        return group.count();
    }

    private boolean ready(ExamAttempt a) {
        int expected=a.getPersonalPaper()!=null?a.getPersonalPaper().getQuestions().size():a.getExam().getQuestions().size();
        return a.getAnswers().size()==expected && a.getAnswers().stream().allMatch(v -> v.getAwardedPoints()!=null);
    }
    @Transactional
    public int publish(Long examId, String actor, int expectedCount) {
        lockExam(examId); var all=prepare(examId);
        var eligible=all.stream().filter(a -> a.getStatus()==AttemptStatus.SUBMITTED && (!items(a).isEmpty() || ShortAnswerReviewService.centrallyGraded(a)) && ready(a)).toList();
        if(eligible.size()!=expectedCount) throw new IllegalStateException("待公布人数已变化，请刷新后确认");
        for(var a:eligible) {
            int total=a.getAnswers().stream().mapToInt(AttemptAnswer::getAwardedPoints).sum();
            a.setScore(total);a.setPassed(total>=a.getExam().getPassScore());a.setStatus(AttemptStatus.GRADED);a.setGradedAt(Instant.now());a.setGradedByEmployeeNo(privateAdmins.actorLabel(actor));
            audit(a,actor,"集中阅卷：公布成绩");
        }
        return eligible.size();
    }
    private void audit(ExamAttempt a,String actor,String action) {
        var revision=new GradingRevision();revision.setAttemptId(a.getId());revision.setActor(privateAdmins.actorLabel(actor));revision.setRecordedAt(Instant.now());revision.setComment(action);
        try { revision.setScoresJson(JSON.writeValueAsString(a.getAnswers().stream().collect(Collectors.toMap(v -> v.getQuestion().getId(),v -> v.getAwardedPoints()==null?-1:v.getAwardedPoints())))); }
        catch(Exception e){throw new IllegalStateException("保存审计记录失败",e);} em.persist(revision);
    }
    private void reopen(ExamAttempt a,String actor,String action) {
        audit(a,actor,action);a.setStatus(AttemptStatus.SUBMITTED);a.setScore(null);a.setPassed(null);a.setGradedAt(null);a.setGradedByEmployeeNo(null);
    }
    @Transactional
    public void revoke(Long examId,Long decisionId,boolean reopenPublished,String actor) {
        lockExam(examId);var d=decisions.findById(decisionId).orElseThrow(() -> new IllegalArgumentException("判定记录不存在"));
        if(!d.getExamId().equals(examId)) throw new IllegalArgumentException("判定记录不属于本场考试");
        if(d.getRevokedAt()!=null) return;
        var all=attempts.findByExamIdOrderByStartedAtDesc(examId).stream().sorted(Comparator.comparing(ExamAttempt::getId)).toList();
        for(var entry:all) {
            var a=lockAttempt(entry.getId());
            if(a.getStatus()!=AttemptStatus.GRADED && a.getStatus()!=AttemptStatus.SUBMITTED) continue;
            for(var item:items(a)) {
                var v=answer(a,item.questionId());boolean affected=v.getBlankMarks().stream().anyMatch(m -> decisionId.equals(m.getDecisionId()));
                if(!affected) continue;
                if(a.getStatus()==AttemptStatus.GRADED && !reopenPublished) throw new IllegalStateException("涉及已公布成绩，请确认撤回成绩后再撤销");
                if(a.getStatus()!=AttemptStatus.GRADED && a.getStatus()!=AttemptStatus.SUBMITTED) continue;
                reopen(a,actor,"撤销填空判定 #"+decisionId);
                v.getBlankMarks().stream().filter(m -> decisionId.equals(m.getDecisionId())).forEach(m -> {m.setCorrect(null);m.setDecisionId(null);m.setRuleId(null);m.setSource("PENDING");});
                recompute(item,v);
            }
        }
        if(d.getRuleId()!=null) disableRule(d.getRuleId(),actor);
        d.setRevokedAt(Instant.now());d.setRevokedBy(privateAdmins.actorLabel(actor));
    }
    @Transactional
    public void disableRule(Long id,String actor) {
        var rule=rules.findById(id).orElseThrow(() -> new IllegalArgumentException("答案记录不存在"));
        em.refresh(rule,LockModeType.PESSIMISTIC_WRITE);rule.setActive(false);rule.setDisabledAt(Instant.now());rule.setDisabledBy(privateAdmins.actorLabel(actor));
    }
    /** Explicit correction, not a side effect of disabling future use. */
    @Transactional
    public int withdrawRuleScores(Long id,boolean reopenPublished,String actor) {
        var initial=marks.findByRuleId(id);
        var examIds=initial.stream().map(m -> m.getAnswer().getAttempt().getExam().getId()).distinct().sorted().toList();
        examIds.forEach(this::lockExam);
        var ids=initial.stream().map(m -> m.getAnswer().getAttempt().getId()).distinct().sorted().toList();
        int count=0;
        for(Long aid:ids) {
            var a=lockAttempt(aid);boolean affected=false;
            for(var item:items(a)) {
                var v=answer(a,item.questionId());
                var selected=v.getBlankMarks().stream().filter(m -> id.equals(m.getRuleId())).toList();
                if(selected.isEmpty()) continue;
                if(a.getStatus()==AttemptStatus.GRADED && !reopenPublished) throw new IllegalStateException("涉及已公布成绩，请确认撤回成绩");
                if(a.getStatus()!=AttemptStatus.GRADED && a.getStatus()!=AttemptStatus.SUBMITTED) continue;
                if(!affected) reopen(a,actor,"撤回可接受答案 #"+id+" 的评分");
                selected.forEach(m -> {m.setCorrect(null);m.setRuleId(null);m.setDecisionId(null);m.setSource("PENDING");});recompute(item,v);affected=true;
            }
            if(affected) count++;
        }
        disableRule(id,actor);return count;
    }
}
