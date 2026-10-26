package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerScoring.*;

@Service @RequiredArgsConstructor
public class ShortAnswerReviewService {
    private final com.huaneng.shanyin.hnexam.service.PrivateAdministratorPolicy privateAdmins;
    private final ExamAttemptRepository attempts;
    private final ExamRepository exams;
    private final ShortAnswerRubricRepository rubrics;
    private final ShortAnswerRubricService standards;
    private final ShortAnswerAutomationService automation;
    private final ObjectMapper json;
    @PersistenceContext private EntityManager em;
    public record Item(Long questionId,String stem,String reference,int points) {}
    public record Target(Long attemptId,Long questionId,long revision) {}
    public record Accept(List<Target> targets,boolean individuallyReviewed) {}
    public record ApplySuggestions(List<Target> targets,boolean ordinaryOnly) {
        public ApplySuggestions(List<Target> targets) { this(targets,false); }
    }
    public record Generate(Long rubricId,String strictness,Long revision) {
        public Generate(Long rubricId) { this(rubricId,null,null); }
        public Generate(Long rubricId,String strictness) { this(rubricId,strictness,null); }
    }
    public record Manual(Target target,Integer points,Double semanticSimilarity,String reason) {
        public Manual(Target target,int points,double semanticSimilarity,String reason) { this(target,Integer.valueOf(points),Double.valueOf(semanticSimilarity),reason); }
    }
    public record Undo(Target target,boolean reopenPublished) {}
    public record Row(Long attemptId,Long questionId,String name,String stem,String reference,String answer,int maximum,
                      String state,Integer suggested,Integer awarded,Double similarity,long revision,Long rubricId,
                      Rubric rubric,Result evaluation,List<String> risks,boolean batchEligible,String sampleMessage,boolean published,
                      boolean automaticZero,boolean safeToApply,String gradingComment,Long currentRubricId) {}
    public record Workspace(List<Row> rows,int pending,int suggested,int reviewRequired,int accepted) {}
    public static boolean isUnanswered(String value) {
        return value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
    /** Caller owns the attempt lock. This never changes an existing final question score. */
    public static boolean initializeEmpty(AttemptAnswer v) {
        if (v.getAwardedPoints()!=null || !isUnanswered(v.getAnswerText())) return false;
        v.setShortScoringPolicyVersion(POLICY);v.setAwardedPoints(0);v.setCorrect(false);v.setSemanticSimilarity(0d);v.setSimilaritySource("EMPTY_ANSWER");
        v.setGradingMethod(GradingMethod.AUTO);v.setGradingComment("未作答，自动记0分");
        v.setShortReviewState("ACCEPTED");v.setAiStatus("SHORT_EMPTY_ZERO");v.setShortRevision(v.getShortRevision()+1);
        v.setShortRunToken(null);v.setShortRunStartedAt(null);v.setShortRubricId(null);
        v.setAiSuggestedPoints(null);v.setAiSuggestedSimilarity(null);v.setAiSuggestionReason(null);v.setAiModel(null);
        v.setShortEvaluationJson(null);v.setShortRisksJson(null);v.setShortSampleApproved(false);v.setShortSampleDisagreed(false);
        return true;
    }
    public static List<Item> items(ExamAttempt a) {
        if(a.getPersonalPaper()!=null) return a.getPersonalPaper().getQuestions().stream().filter(q->q.getSnapshotType()==QuestionType.SHORT_ANSWER)
                .map(q->new Item(q.getQuestion().getId(),q.getSnapshotStem(),q.getSnapshotAnswer(),q.getPoints())).toList();
        return a.getExam().getQuestions().stream().filter(q->(q.getSnapshotType()==null?q.getQuestion().getType():q.getSnapshotType())==QuestionType.SHORT_ANSWER)
                .map(q->new Item(q.getQuestion().getId(),q.getSnapshotStem()==null?q.getQuestion().getStem():q.getSnapshotStem(),q.getSnapshotAnswer()==null?q.getQuestion().getAnswer():q.getSnapshotAnswer(),q.getPoints())).toList();
    }
    public static boolean centrallyGraded(ExamAttempt a) {
        return a.getAnswers().stream().anyMatch(v->!v.getBlankMarks().isEmpty() || v.getShortReviewState()!=null);
    }
    private Item item(ExamAttempt a,Long id) { return items(a).stream().filter(q->q.questionId().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("简答题不存在")); }
    private AttemptAnswer answer(ExamAttempt a,Long id) { return a.getAnswers().stream().filter(v->v.getQuestion().getId().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("答卷作答缺失，请检查历史数据")); }
    private ExamAttempt lock(Long id) { var a=attempts.findLockedById(id).orElseThrow(()->new IllegalArgumentException("答卷不存在"));em.refresh(a,LockModeType.NONE);return a; }
    private void allowed(ExamAttempt a,Long examId,boolean published) {
        if(!a.getExam().getId().equals(examId)) throw new IllegalArgumentException("答卷不属于本场考试");
        if(a.getExam().getStatus()==ExamStatus.CANCELLED || (a.getStatus()!=AttemptStatus.SUBMITTED && !(published && a.getStatus()==AttemptStatus.GRADED))) throw new IllegalStateException("答卷当前不能修改评分");
    }
    private void lockExam(Long id) {
        var exam=exams.findLockedById(id).orElseThrow(()->new IllegalArgumentException("考试不存在"));
        if(exam.getStatus()==ExamStatus.CANCELLED) throw new IllegalStateException("考试已取消");
    }
    private ShortAnswerRubric latest(Item q) { return rubrics.findFirstByQuestionIdAndQuestionVersionOrderByIdDesc(q.questionId(),version(q.stem(),q.reference())).orElse(null); }
    private ShortAnswerRubric current(ExamAttempt a,Item q) {
        Long id=a.getPersonalPaper()!=null
                ?a.getPersonalPaper().getQuestions().stream().filter(x->x.getQuestion().getId().equals(q.questionId())).map(x->Optional.ofNullable(x.getShortRubricId())).findFirst().orElse(Optional.empty()).orElse(null)
                :a.getExam().getQuestions().stream().filter(x->x.getQuestion().getId().equals(q.questionId())).map(x->Optional.ofNullable(x.getShortRubricId())).findFirst().orElse(Optional.empty()).orElse(null);
        return id==null?latest(q):rubrics.findById(id).orElse(null);
    }
    private Result result(AttemptAnswer v) {
        if(v.getShortEvaluationJson()==null) return null;
        try {return json.readValue(v.getShortEvaluationJson(),Result.class);}catch(Exception e){throw new IllegalStateException("逐点评分记录格式错误");}
    }
    private boolean validBasis(Item q,AttemptAnswer v,ShortAnswerRubric current) {
        var evaluation=result(v);
        if(evaluation!=null && evaluation.referenceScore()!=null)
            return (current==null || !"CONFIRMED".equals(current.getStatus()))
                    && version(q.stem(),q.reference()).equals(evaluation.referenceScore().questionVersion());
        return current!=null && Set.of("CONFIRMED","AUTO_READY").contains(current.getStatus()) && current.getId().equals(v.getShortRubricId());
    }
    private Evaluation checked(Item q,AttemptAnswer v,ShortAnswerRubric current) {
        if(!validBasis(q,v,current)) throw new IllegalStateException("评分标准已更新，请重新生成评分");
        var evaluation=result(v);
        if(POLICY.equals(v.getShortScoringPolicyVersion())) return evaluateAtomic(standards.decode(current),new Decision(FORMAT,POLICY,evaluation.findings(),evaluation.concerns()),Objects.toString(v.getAnswerText(),""),q.points());
        return evaluation.referenceScore()!=null
                ?evaluateReference(evaluation,Objects.toString(v.getAnswerText(),""),q.points(),version(q.stem(),q.reference()))
                :evaluate(standards.decode(current),evaluation,Objects.toString(v.getAnswerText(),"") ,q.points());
    }
    private List<String> risks(AttemptAnswer v) {
        if(v.getShortRisksJson()==null) return new ArrayList<>();
        try {return new ArrayList<>(json.readValue(v.getShortRisksJson(),new TypeReference<List<String>>(){}));}catch(Exception e){return new ArrayList<>(List.of("评分记录需人工核对"));}
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("保存评分记录失败");}}
    private boolean borderline(ExamAttempt a) {
        int total=0;
        for(var v:a.getAnswers()) {Integer p=v.getAwardedPoints()!=null?v.getAwardedPoints():v.getAiSuggestedPoints();if(p==null)return false;total+=p;}
        return Math.abs(total-a.getExam().getPassScore())<=Math.max(1,(int)Math.ceil(a.getExam().getTotalScore()*.02));
    }
    private String sampleMessage(List<ExamAttempt> all,Long qid,Long rid) {
        var pool=all.stream().filter(a->a.getStatus()==AttemptStatus.SUBMITTED || a.getStatus()==AttemptStatus.GRADED)
                .flatMap(a->a.getAnswers().stream()).filter(v->qid.equals(v.getQuestion().getId()) && Objects.equals(rid,v.getShortRubricId())).toList();
        if(pool.stream().anyMatch(AttemptAnswer::isShortSampleDisagreed)) return "本题抽查发现改分，请修订标准后重评，或逐份复核";
        long distinct=pool.stream().filter(v->v.getShortEvaluationJson()!=null).map(v->Objects.toString(v.getAnswerText(),"").strip()).distinct().count();
        long checked=pool.stream().filter(v->v.getShortEvaluationJson()!=null && v.isShortSampleApproved()).map(v->Objects.toString(v.getAnswerText(),"").strip()).distinct().count();
        long required=Math.min(3,distinct);
        return checked>=required && required>0 ? "抽查已完成，可批量确认普通答案" : "请先逐份核对不同作答："+checked+" / "+required;
    }
    @Transactional
    public Workspace workspace(Long examId) {
        lockExam(examId);
        var all=attempts.findByExamIdOrderByStartedAtDesc(examId).stream().sorted(Comparator.comparing(ExamAttempt::getId)).toList();var rows=new ArrayList<Row>();
        for (var entry:all) {
            if(entry.getStatus()!=AttemptStatus.SUBMITTED) continue;
            var a=lock(entry.getId());
            if(a.getStatus()!=AttemptStatus.SUBMITTED) continue;
            for(var q:items(a)) initializeEmpty(answer(a,q.questionId()));
        }
        for(var a:all) {
            if(a.getStatus()!=AttemptStatus.SUBMITTED && a.getStatus()!=AttemptStatus.GRADED) continue;
            for(var q:items(a)) {
                var v=answer(a,q.questionId());var current=current(a,q);var evaluation=result(v);
                var used=evaluation!=null && evaluation.referenceScore()!=null?null:
                        v.getShortRubricId()==null?current:rubrics.findById(v.getShortRubricId()).orElse(current);
                String state=v.getAwardedPoints()!=null?"ACCEPTED":Objects.toString(v.getShortReviewState(),"NOT_STARTED");
                if(v.getAwardedPoints()==null && !"RUNNING".equals(state)) {
                    if(evaluation!=null && evaluation.referenceScore()!=null) {
                        if(!validBasis(q,v,current)) state="STALE";
                    } else if((current==null || !Set.of("CONFIRMED","AUTO_READY").contains(current.getStatus())) && !"FAILED".equals(state)) state="NEEDS_RUBRIC";
                    else if(current!=null && v.getShortRubricId()!=null && !v.getShortRubricId().equals(current.getId())) state="STALE";
                }
                if("RUNNING".equals(state) && v.getShortRunStartedAt()!=null && v.getShortRunStartedAt().isBefore(Instant.now().minusSeconds(150))) state="FAILED";
                var warning=risks(v);boolean boundary=!POLICY.equals(v.getShortScoringPolicyVersion()) && borderline(a);
                if(boundary && v.getAwardedPoints()==null) warning.add("可能影响及格结果，请单独复核");
                String sample=POLICY.equals(v.getShortScoringPolicyVersion())?"AI建议分，须人工确认最终分":sampleMessage(all,q.questionId(),v.getShortRubricId());
                if(v.getAwardedPoints()==null && sample.startsWith("本题抽查发现改分")) warning.add("本题存在人工改分，请重点复核");
                boolean eligible=state.equals("SUGGESTED") && warning.isEmpty() && sample.startsWith("抽查已完成");
                rows.add(new Row(a.getId(),q.questionId(),a.getUser().getName(),q.stem(),q.reference(),Objects.toString(v.getAnswerText(),""),q.points(),state,v.getAiSuggestedPoints(),v.getAwardedPoints(),v.getAiSuggestedSimilarity(),v.getShortRevision(),used==null?null:used.getId(),used==null || used.getRubricJson()==null?null:standards.decode(used),result(v),warning,eligible,sample,a.getStatus()==AttemptStatus.GRADED,
                        "SHORT_EMPTY_ZERO".equals(v.getAiStatus()),state.equals("SUGGESTED") && warning.isEmpty(),v.getGradingComment(),current==null?null:current.getId()));
            }
        }
        rows.sort(Comparator.comparing(Row::questionId).thenComparing(Row::attemptId));
        return new Workspace(rows,(int)rows.stream().filter(r->!r.state().equals("ACCEPTED")).count(),(int)rows.stream().filter(r->r.state().equals("SUGGESTED")).count(),(int)rows.stream().filter(r->!r.risks().isEmpty()&&!r.state().equals("ACCEPTED")).count(),(int)rows.stream().filter(r->r.state().equals("ACCEPTED")).count());
    }
    public Map<String,String> suggest(Long attemptId,Long qid) {
        return suggest(attemptId,qid,null);
    }
    public Map<String,String> suggest(Long attemptId,Long qid,Generate expected) {
        return automation.startForAttempt(attemptId, null);
    }
    private void revision(AttemptAnswer v,Target target) {
        if(v.getShortRevision()!=target.revision()) throw new IllegalStateException("本题评分已变化，请刷新后重新确认");
    }
    private void audit(ExamAttempt a,String actor,String message) {
        var r=new GradingRevision();r.setAttemptId(a.getId());r.setActor(privateAdmins.actorLabel(actor));r.setRecordedAt(Instant.now());r.setComment(message);
        r.setScoresJson(encode(a.getAnswers().stream().map(v->Map.of("questionId",v.getQuestion().getId(),"points",v.getAwardedPoints()==null?-1:v.getAwardedPoints(),"revision",v.getShortRevision())).toList()));em.persist(r);
    }
    private void apply(ExamAttempt a,AttemptAnswer v,Item q,int score,Double similarity,String actor,String comment) {
        v.setAwardedPoints(score);v.setSemanticSimilarity(similarity);v.setSimilaritySource(similarity==null?null:"MANUAL_REVIEW");v.setCorrect(correctByScore(score,q.points()));v.setShortScoringPolicyVersion(POLICY);v.setGradingMethod(GradingMethod.MANUAL);v.setGradingComment(comment);
        v.setShortRunToken(null);v.setShortReviewState("ACCEPTED");v.setAiStatus("SHORT_ACCEPTED");v.setShortRevision(v.getShortRevision()+1);
        a.setScore(null);a.setPassed(null);a.setGradedAt(null);a.setGradedByEmployeeNo(null);a.setStatus(AttemptStatus.SUBMITTED);
        audit(a,actor,"简答题 #"+q.questionId()+"："+comment+"；AI原建议="+Objects.toString(v.getAiSuggestedPoints(),"无")+"；最终分="+score);
    }
    @Transactional
    public int accept(Long examId,Accept request,String actor) {
        return accept(examId, request, actor, false, false);
    }
    @Transactional
    public int applySuggestions(Long examId,ApplySuggestions request,String actor) {
        if (request == null) throw new IllegalArgumentException("请选择有效AI建议");
        return accept(examId, new Accept(request.targets(), false), actor, true, request.ordinaryOnly());
    }
    private int accept(Long examId,Accept request,String actor,boolean direct,boolean ordinaryOnly) {
        if(request==null || request.targets()==null || request.targets().isEmpty() || request.targets().size()>1000 || (request.individuallyReviewed()&&request.targets().size()!=1)) throw new IllegalArgumentException("请选择评分记录；疑难答案须逐份确认");
        if(request.targets().stream().anyMatch(t->t==null||t.attemptId()==null||t.questionId()==null) || request.targets().stream().map(t->t.attemptId()+":"+t.questionId()).distinct().count()!=request.targets().size()) throw new IllegalArgumentException("评分记录重复或无效");
        lockExam(examId);
        var all=attempts.findByExamIdOrderByStartedAtDesc(examId);
        for(var target:request.targets().stream().sorted(Comparator.comparing(Target::attemptId).thenComparing(Target::questionId)).toList()) {
            var a=lock(target.attemptId());allowed(a,examId,false);var q=item(a,target.questionId());var v=answer(a,q.questionId());revision(v,target);
            if(v.getAwardedPoints()!=null || !Set.of("SUGGESTED","REVIEW_REQUIRED").contains(Objects.toString(v.getShortReviewState(),""))) throw new IllegalStateException("请选择尚未确认的有效AI建议");
            var current=current(a,q);var checked=checked(q,v,current);
            if((ordinaryOnly || request.targets().size()>1) && (!POLICY.equals(v.getShortScoringPolicyVersion()) || checked.reviewRequired()))
                throw new IllegalStateException("旧版或疑难建议须逐份人工评分");
            v.setShortSampleApproved(request.individuallyReviewed());
            apply(a,v,q,checked.points(),result(v).semanticSimilarity(),actor,direct?"人工确认AI建议分数":request.individuallyReviewed()?"已逐点核对并确认AI建议":"人工批量确认AI建议");
            em.flush();
        }
        return request.targets().size();
    }
    @Transactional
    public void manual(Long examId,Manual request,String actor) {
        if(request==null || request.target()==null || request.target().attemptId()==null || request.target().questionId()==null || request.points()==null
                || (request.reason()!=null && request.reason().length()>2000)
                || (request.semanticSimilarity()!=null && (!Double.isFinite(request.semanticSimilarity()) || request.semanticSimilarity()<0 || request.semanticSimilarity()>100))) throw new IllegalArgumentException("请填写有效得分，说明最多2000字");
        lockExam(examId);var a=lock(request.target().attemptId());allowed(a,examId,false);var q=item(a,request.target().questionId());var v=answer(a,q.questionId());revision(v,request.target());
        if(request.points()<0 || request.points()>q.points()) throw new IllegalArgumentException("分数超出本题范围");
        if(v.getAwardedPoints()!=null) throw new IllegalStateException("已确认评分，请先撤销确认");
        boolean disagreed=v.getAiSuggestedPoints()!=null && !v.getAiSuggestedPoints().equals(request.points());
        if(disagreed) automation.invalidateDecision(v);
        v.setShortSampleDisagreed(disagreed || v.isShortSampleDisagreed());v.setShortSampleApproved(v.getAiSuggestedPoints()!=null && !v.isShortSampleDisagreed());
        var current=current(a,q);
        boolean validAi=validBasis(q,v,current)
                && Set.of("SUGGESTED","REVIEW_REQUIRED").contains(Objects.toString(v.getShortReviewState(),""));
        Double semantic=request.semanticSimilarity()!=null?request.semanticSimilarity():validAi?v.getAiSuggestedSimilarity():null;
        apply(a,v,q,request.points(),semantic,actor,isUnanswered(request.reason())?"人工评分（未填写说明）":"人工评分："+request.reason().strip());
        if(request.semanticSimilarity()==null && semantic!=null) v.setSimilaritySource("AI_REFERENCE");
    }
    @Transactional
    public void undo(Long examId,Undo request,String actor) {
        if(request==null || request.target()==null || request.target().attemptId()==null || request.target().questionId()==null) throw new IllegalArgumentException("请选择要撤销的评分");
        lockExam(examId);var a=lock(request.target().attemptId());allowed(a,examId,true);var v=answer(a,request.target().questionId());item(a,request.target().questionId());revision(v,request.target());
        if("SHORT_EMPTY_ZERO".equals(v.getAiStatus())) throw new IllegalStateException("未作答自动记0分，无需撤销或重新评阅");
        if(a.getStatus()==AttemptStatus.GRADED && !request.reopenPublished()) throw new IllegalStateException("涉及已公布成绩，请明确确认撤回");
        audit(a,actor,"撤销简答确认，原得分="+v.getAwardedPoints()+"，题目 #"+request.target().questionId());
        v.setAwardedPoints(null);v.setCorrect(null);v.setSemanticSimilarity(null);v.setShortRunToken(null);
        v.setShortReviewState(v.getShortEvaluationJson()!=null && v.getAiSuggestedPoints()!=null?(risks(v).isEmpty()?"SUGGESTED":"REVIEW_REQUIRED"):"NOT_STARTED");
        v.setAiStatus(v.getShortEvaluationJson()!=null?"SHORT_READY":null);v.setSimilaritySource(null);v.setGradingMethod(null);v.setGradingComment(null);
        v.setShortRevision(v.getShortRevision()+1);v.setShortSampleApproved(false);
        a.setStatus(AttemptStatus.SUBMITTED);a.setScore(null);a.setPassed(null);a.setGradedAt(null);a.setGradedByEmployeeNo(null);
    }
}
