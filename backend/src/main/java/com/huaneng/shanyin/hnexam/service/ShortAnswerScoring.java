package com.huaneng.shanyin.hnexam.service;

import java.util.*;

/** The model judges evidence, never calculates or publishes the final total. */
public final class ShortAnswerScoring {
    private ShortAnswerScoring() {}
    public record Point(String key,String label,String source,int weight,String kind,String constraint,boolean partialAllowed,boolean critical) {}
    public record Rubric(List<Point> points,String guidance) {}
    public record Finding(String key,String verdict,String evidence,String reason) {}
    public record Result(List<Finding> findings,double semanticSimilarity,List<String> concerns) {}
    public record Evaluation(int points,boolean reviewRequired,List<String> risks) {}
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
