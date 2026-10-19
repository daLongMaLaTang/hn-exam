package com.huaneng.shanyin.hnexam.repository;
import com.huaneng.shanyin.hnexam.entity.FillBlankRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FillBlankRuleRepository extends JpaRepository<FillBlankRule,Long> {
    List<FillBlankRule> findByQuestionIdAndQuestionVersionAndActiveTrue(Long questionId,String version);
    Optional<FillBlankRule> findByQuestionIdAndQuestionVersionAndSlotAndAnswerHash(Long questionId,String version,int slot,String hash);
    List<FillBlankRule> findByQuestionIdInOrderByIdDesc(Collection<Long> questionIds);
}
