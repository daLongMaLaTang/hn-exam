package com.huaneng.shanyin.hnexam.service;

import java.util.*;

/** The model judges evidence, never calculates or publishes the final total. */
public final class ShortAnswerScoring {
    private ShortAnswerScoring() {}
    public record Point(String key,String label,String source,int weight,String kind,String constraint,boolean partialAllowed,boolean critical) {}
    public static final String POLICY = "SHORT_ATOMIC_V2";
    public static final int FORMAT = 2;
    public static final String NORMALIZATION = "TRIM_CRLF_V1";
    /** All points are leaves. Alternatives belong inside one constraint, never duplicate weights. */
    public record Rubric(List<Point> points,String guidance,Integer formatVersion,String policyVersion,List<String> issues) {
        public Rubric(List<Point> points,String guidance) { this(points,guidance,null,null,null); }
    }
    public record Decision(int formatVersion,String policyVersion,List<Finding> findings,List<String> concerns) {}
    public static String normalize(String text) { return Objects.toString(text,"").replace("\r\n","\n").replace('\r','\n').strip(); }
    public static String contentVersion(String stem,String reference) {
        return FillBlankReviewService.hash(NORMALIZATION+"\0"+normalize(stem)+"\0"+normalize(reference));
    }
    public static boolean correctByScore(int score,int maximum) {
        if(maximum<=0 || score<0 || score>maximum) throw new IllegalArgumentException("试卷快照满分或得分无效");
        return 10L*score>3L*maximum;
    }
    public static void validateAtomic(Rubric r,String reference) {
        validate(r,reference);
        if(!Objects.equals(r.formatVersion(),FORMAT) || !POLICY.equals(r.policyVersion()) || r.issues()==null)
            throw new IllegalArgumentException("评分标准版本不符");
        if(!r.issues().isEmpty()) throw new IllegalArgumentException("评分标准存在歧义，请人工核对");
        var unique=new HashSet<String>();var requirements=new HashSet<String>();
        for(var p:r.points()) {
            if(p.partialAllowed() || p.constraint()==null || p.constraint().isBlank())
                throw new IllegalArgumentException("评分点须为完整原子点，部分分请预先拆点");
            if(!unique.add(normalize(p.label())) || !requirements.add(normalize(p.source())+"\0"+p.kind()+"\0"+normalize(p.constraint())) || p.source().length()>10000)
                throw new IllegalArgumentException("评分点重复或原文过长");
        }
    }
    public static Evaluation evaluateAtomic(Rubric rubric,Decision result,String answer,int maximum) {
        if(maximum<=0 || result==null || result.formatVersion()!=FORMAT || !POLICY.equals(result.policyVersion())
                || result.findings()==null || result.findings().size()!=rubric.points().size()
                || result.concerns()==null || result.concerns().size()>20)
            throw new IllegalArgumentException("AI逐点结果版本或格式错误");
        var findings=new HashMap<String,Finding>();
        for(var f:result.findings()) {
            if(f==null || f.key()==null || findings.put(f.key(),f)!=null
                    || !Set.of("FULL","NONE","REVIEW").contains(Objects.toString(f.verdict(),""))
                    || f.reason()==null || f.reason().isBlank() || f.reason().length()>1000
                    || f.evidence()==null || f.evidence().length()>10000
                    || (!f.evidence().isEmpty() && (f.evidence().isBlank() || !Objects.toString(answer,"").contains(f.evidence())))
                    || ("FULL".equals(f.verdict()) && f.evidence().isBlank()))
                throw new IllegalArgumentException("AI评分点或作答原文依据无效");
        }
        var risks=new ArrayList<String>();
        for(var c:result.concerns()) {
            if(c==null || c.length()>1000) throw new IllegalArgumentException("AI疑难说明格式错误");
            if(!c.isBlank()) risks.add(c);
        }
        long total=0,earned=0;
        for(var p:rubric.points()) {
            var f=findings.get(p.key());
            if(f==null) throw new IllegalArgumentException("AI遗漏评分点");
            total+=p.weight();
            if("FULL".equals(f.verdict())) earned+=p.weight();
            if("REVIEW".equals(f.verdict())) risks.add(p.label()+"："+f.reason());
        }
        int points=java.math.BigDecimal.valueOf(maximum).multiply(java.math.BigDecimal.valueOf(earned))
                .divide(java.math.BigDecimal.valueOf(total),0,java.math.RoundingMode.HALF_UP).intValueExact();
        return new Evaluation(points,!risks.isEmpty(),risks.stream().distinct().toList());
    }
    public record Finding(String key,String verdict,String evidence,String reason) {}
    public record ReferenceScore(int points,String reason,String evidence,String questionVersion) {}
    public record Result(List<Finding> findings,double semanticSimilarity,List<String> concerns,String strictness,ReferenceScore referenceScore) {
        public Result(List<Finding> findings,double semanticSimilarity,List<String> concerns) {
            this(findings,semanticSimilarity,concerns,null,null);
        }
    }
    public enum Strictness {
        LENIENT("宽松", "关键意思正确且已表达即可认可，允许合理概括、同义表述、非关键措辞或表述完整度不足；不可臆测未写出的关键知识点。"),
        BALANCED("适中", "按关键知识点的实际覆盖程度评分，接受准确的同义表述；要点缺失或只部分表达应扣分。"),
        STRICT("严格", "要求每个要点表达完整、准确，对遗漏的限定条件、不完整表述严格扣分；准确的同义表述仍应认可，不要求逐字背诵。");
        private final String label;
        private final String guidance;
        Strictness(String label,String guidance) { this.label=label;this.guidance=guidance; }
        public String label() { return label; }
        public String instruction() {
            return " 本轮判卷松紧度："+label+"。"+guidance
                    +" 所有档位均不得放宽安全关键要求、数字单位、否定含义及明确操作顺序，不得为未作答要点送分。"
                    +"如提供已确认评分标准，必须服从其权重、明确约束和部分分规则，松紧度不得覆盖这些规则。";
        }
        public static Strictness parse(String value) {
            if(value==null) return BALANCED;
            try { return valueOf(value); }
            catch(IllegalArgumentException e) { throw new IllegalArgumentException("请选择宽松、适中或严格的判卷松紧度"); }
        }
    }
    public record Evaluation(int points,boolean reviewRequired,List<String> risks) {}
    public static Evaluation evaluateReference(Result result,String answer,int maximum,String questionVersion) {
        if(result==null || result.referenceScore()==null || result.concerns()==null || result.concerns().size()>20
                || !Double.isFinite(result.semanticSimilarity()) || result.semanticSimilarity()<0 || result.semanticSimilarity()>100)
            throw new IllegalArgumentException("AI参考答案评分格式不正确");
        var score=result.referenceScore();
        if(score.points()<0 || score.points()>maximum || !Objects.equals(questionVersion,score.questionVersion())
                || score.reason()==null || score.reason().isBlank() || score.reason().length()>2000
                || score.evidence()==null || score.evidence().length()>10000
                || (!score.evidence().isEmpty() && (score.evidence().isBlank() || !answer.contains(score.evidence())))
                || (score.points()>0 && score.evidence().isBlank()))
            throw new IllegalArgumentException("AI得分越界或缺少可核对的作答原文");
        var risks=new ArrayList<String>();
        for(var concern:result.concerns()) {
            if(concern==null || concern.length()>1000) throw new IllegalArgumentException("AI疑难说明格式不正确");
            if(!concern.isBlank()) risks.add(concern);
        }
        return new Evaluation(score.points(),!risks.isEmpty(),risks.stream().distinct().toList());
    }
    public static String version(String stem,String reference) { return FillBlankReviewService.hash("short-rubric-v1\0"+stem+"\0"+reference); }
    public static void validate(Rubric r,String reference) {
        if(r==null || r.points()==null || r.points().isEmpty() || r.points().size()>40) throw new IllegalArgumentException("评分标准需要1至40个评分点");
        if(r.guidance()!=null && r.guidance().length()>3000) throw new IllegalArgumentException("评分说明不能超过3000字");
        var keys=new HashSet<String>();
        for(var p:r.points()) {
            if(p==null || p.key()==null || !p.key().matches("[A-Za-z0-9_-]{1,30}") || !keys.add(p.key())) throw new IllegalArgumentException("评分点标识重复或无效");
            if(p.label()==null || p.label().isBlank() || p.label().length()>500 || p.weight()<1 || p.weight()>100) throw new IllegalArgumentException("请填写评分点内容及1至100的分值权重");
            if(p.source()==null || p.source().isBlank() || !reference.contains(p.source())) throw new IllegalArgumentException("每个评分点的依据须为参考答案中的完整原文片段");
            if(!Set.of("CONCEPT","NUMBER","ORDER","CONDITION").contains(Objects.toString(p.kind(),""))) throw new IllegalArgumentException("评分点类型无效");
            if(p.constraint()!=null && p.constraint().length()>1000) throw new IllegalArgumentException("判分要求过长");
            if(!"CONCEPT".equals(p.kind()) && (p.constraint()==null || p.constraint().isBlank())) throw new IllegalArgumentException("数值、顺序和条件评分点须填写具体判分要求");
        }
    }
    public static Evaluation evaluate(Rubric rubric,Result result,String answer,int maximum) {
        if(result==null || result.findings()==null || result.findings().size()!=rubric.points().size()
                || !Double.isFinite(result.semanticSimilarity()) || result.semanticSimilarity()<0 || result.semanticSimilarity()>100
                || result.concerns()==null || result.concerns().size()>20) throw new IllegalArgumentException("AI评分点不完整或格式不正确");
        var findings=new HashMap<String,Finding>();
        for(var f:result.findings()) {
            if(f==null || f.key()==null || findings.put(f.key(),f)!=null || !Set.of("MET","PARTIAL","MISSING","CONTRADICTED","UNCERTAIN").contains(Objects.toString(f.verdict(),""))
                    || f.reason()==null || f.reason().isBlank() || f.reason().length()>1000 || f.evidence()==null || f.evidence().length()>10000) throw new IllegalArgumentException("AI评分点格式不正确");
        }
        var risks=new ArrayList<String>();
        for(String concern:result.concerns()) {
            if(concern==null || concern.length()>1000) throw new IllegalArgumentException("AI疑难说明格式不正确");
            if(!concern.isBlank()) risks.add(concern);
        }
        double earned=0; int total=0;
        for(var p:rubric.points()) {
            var f=findings.get(p.key()); if(f==null) throw new IllegalArgumentException("AI遗漏评分点");
            boolean evidenceValid=!f.evidence().isBlank() && Objects.toString(answer,"").contains(f.evidence());
            if(!f.evidence().isEmpty() && !evidenceValid) throw new IllegalArgumentException("AI引用了作答中不存在的文字");
            if(Set.of("MET","PARTIAL","CONTRADICTED").contains(f.verdict()) && !evidenceValid) throw new IllegalArgumentException("AI没有提供可核对的作答原文依据");
            if(f.verdict().equals("PARTIAL") && !p.partialAllowed()) throw new IllegalArgumentException("该评分点未允许部分分");
            total+=p.weight(); earned+=p.weight()*(f.verdict().equals("MET")?1:f.verdict().equals("PARTIAL")?.5:0);
            if(Set.of("UNCERTAIN","CONTRADICTED").contains(f.verdict())) risks.add(p.label()+"："+f.reason());
            if(!p.kind().equals("CONCEPT")) risks.add(p.label()+"：请核对数值、顺序或适用条件");
            if(p.critical() && !f.verdict().equals("MET")) risks.add(p.label()+"：关键要求未完全满足");
        }
        return new Evaluation((int)Math.round(maximum*earned/total),!risks.isEmpty(),risks.stream().distinct().toList());
    }
}
