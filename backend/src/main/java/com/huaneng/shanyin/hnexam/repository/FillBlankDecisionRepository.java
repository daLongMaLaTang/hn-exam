package com.huaneng.shanyin.hnexam.repository;
import com.huaneng.shanyin.hnexam.entity.FillBlankDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface FillBlankDecisionRepository extends JpaRepository<FillBlankDecision,Long> {
    List<FillBlankDecision> findByExamIdOrderByIdDesc(Long examId);
}
