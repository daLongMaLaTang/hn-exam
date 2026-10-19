package com.huaneng.shanyin.hnexam.repository;
import com.huaneng.shanyin.hnexam.entity.ShortAnswerRubric;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ShortAnswerRubricRepository extends JpaRepository<ShortAnswerRubric,Long> {
    Optional<ShortAnswerRubric> findFirstByQuestionIdAndQuestionVersionOrderByIdDesc(Long questionId,String questionVersion);
}
