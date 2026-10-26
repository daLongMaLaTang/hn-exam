package com.huaneng.shanyin.hnexam;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.huaneng.shanyin.hnexam.service.ShortAnswerScoring.*;
import static org.assertj.core.api.Assertions.*;
class ShortAnswerAtomicTest {
    Rubric rule(int... weights) {
        var points=new ArrayList<Point>();
        for(int i=0;i<weights.length;i++) points.add(new Point("p"+i,"点"+i,"原文",weights[i],"ORDER","先甲后乙",false,true));
        return new Rubric(points,"",FORMAT,POLICY,List.of());
    }
    Decision result(String... verdicts) {
        var f=new ArrayList<Finding>();
        for(int i=0;i<verdicts.length;i++) f.add(new Finding("p"+i,verdicts[i],"原文","依据"));
        return new Decision(FORMAT,POLICY,f,List.of());
    }
    @Test void weightsRoundOnceHalfUpAndErrorsStayLocal() {
        assertThat(evaluateAtomic(rule(3,3,2,2),result("FULL","FULL","FULL","NONE"),"原文",10).points()).isEqualTo(8);
        assertThat(evaluateAtomic(rule(1,1,1,1),result("FULL","FULL","NONE","NONE"),"原文",1).points()).isEqualTo(1);
        assertThat(evaluateAtomic(rule(1,1,1),result("FULL","FULL","NONE"),"原文",2).points()).isEqualTo(1);
        assertThat(evaluateAtomic(rule(1,1),result("FULL","NONE"),"原文",10).reviewRequired()).isFalse();
        assertThat(evaluateAtomic(rule(1,1),result("FULL","REVIEW"),"原文",10).reviewRequired()).isTrue();
    }
    @Test void invalidEvidenceVersionsAndPartialAreRejected() {
        assertThatThrownBy(()->evaluateAtomic(rule(1),result("FULL"),"其他文字",10)).isInstanceOf(IllegalArgumentException.class);
        for(String invalid:List.of("PARTIAL","MET","UNKNOWN")) assertThatThrownBy(()->evaluateAtomic(rule(1),result(invalid),"原文",10)).isInstanceOf(IllegalArgumentException.class);
        var f=new Finding("p0","FULL","原文","满足");
        for(var d:List.of(new Decision(1,POLICY,List.of(f),List.of()),new Decision(2,"OTHER",List.of(f),List.of()),new Decision(2,POLICY,List.of(f,f),List.of())))
            assertThatThrownBy(()->evaluateAtomic(rule(1),d,"原文",10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->evaluateAtomic(rule(1,1),result("FULL"),"原文",10)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void scoreBoundaryAndConservativeNormalization() {
        for(int[] pair:List.of(new int[]{3,10},new int[]{6,20},new int[]{1,5})) assertThat(correctByScore(pair[0],pair[1])).isFalse();
        for(int[] pair:List.of(new int[]{4,10},new int[]{7,20},new int[]{2,5})) assertThat(correctByScore(pair[0],pair[1])).isTrue();
        assertThat(contentVersion("题\r\n目 "," 答案 ")).isEqualTo(contentVersion("题\n目","答案"));
        for(String s:List.of("不允许","允许1m","允许1cm","允 许")) assertThat(contentVersion("题",s)).isNotEqualTo(contentVersion("题","允许"));
    }
    @Test void rejectsAmbiguousAndDuplicateRules() {
        validateAtomic(rule(1),"原文");
        assertThatThrownBy(()->validateAtomic(new Rubric(rule(1).points(),"",2,POLICY,List.of("歧义")),"原文")).hasMessageContaining("歧义");
        var p=rule(1).points().get(0);
        assertThatThrownBy(()->validateAtomic(new Rubric(List.of(p,p),"",2,POLICY,List.of()),"原文")).hasMessageContaining("重复");
        assertThatThrownBy(()->validateAtomic(new Rubric(List.of(new Point("p","点","原文",1,"CONCEPT","要求",true,false)),"",2,POLICY,List.of()),"原文")).hasMessageContaining("原子");
    }
}
