package com.huaneng.shanyin.hnexam;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerScoring.*;
import static org.assertj.core.api.Assertions.*;
class ShortAnswerScoringTest {
    private Point point(String key,int weight,String kind,boolean partial,boolean critical) {return new Point(key,key,key,weight,kind,"具体要求",partial,critical);}
    @Test void computesWeightedRoundedTotalWithoutUsingSimilarity() {
        var rubric=new Rubric(List.of(point("甲",2,"CONCEPT",false,false),point("乙",1,"CONCEPT",true,false)),"");
        var result=new Result(List.of(new Finding("甲","MET","甲","满足"),new Finding("乙","PARTIAL","乙","缺一部分")),5,List.of());
        assertThat(evaluate(rubric,result,"甲乙",10).points()).isEqualTo(8);
        assertThat(evaluate(rubric,result,"甲乙",10).reviewRequired()).isFalse();
    }
    @Test void refusesInventedEvidenceMissingPointsAndDisallowedPartialCredit() {
        var rubric=new Rubric(List.of(point("a",1,"CONCEPT",false,false)),"");
        assertThatThrownBy(()->evaluate(rubric,new Result(List.of(new Finding("a","MET","不存在","正确")),100,List.of()),"考生原文",10)).hasMessageContaining("不存在");
        assertThatThrownBy(()->evaluate(rubric,new Result(List.of(),100,List.of()),"原文",10)).hasMessageContaining("不完整");
        assertThatThrownBy(()->evaluate(rubric,new Result(List.of(new Finding("a","PARTIAL","原文","部分")),100,List.of()),"原文",10)).hasMessageContaining("部分分");
    }
    @Test void routesOrderNumbersContradictionsAndMissingCriticalRequirementsToReview() {
        for(String kind:List.of("NUMBER","ORDER","CONDITION")) {
            var rubric=new Rubric(List.of(point("a",1,kind,false,false)),"");
            assertThat(evaluate(rubric,new Result(List.of(new Finding("a","MET","原文","满足")),100,List.of()),"原文",10).reviewRequired()).isTrue();
        }
        var critical=new Rubric(List.of(point("a",1,"CONCEPT",false,true)),"");
        assertThat(evaluate(critical,new Result(List.of(new Finding("a","MISSING","","遗漏")),90,List.of()),"其他内容",10).reviewRequired()).isTrue();
    }
    @Test void draftRequiresRealSourceAndUniqueKeys() {
        var p=new Point("p1","要点","原文",1,"CONCEPT","",false,false);
        validate(new Rubric(List.of(p),""),"完整原文内容");
        assertThatThrownBy(()->validate(new Rubric(List.of(p,p),""),"原文")).hasMessageContaining("重复");
        assertThatThrownBy(()->validate(new Rubric(List.of(p),""),"其他内容")).hasMessageContaining("原文");
    }
}
