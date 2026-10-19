package com.huaneng.shanyin.hnexam.repository;
import com.huaneng.shanyin.hnexam.entity.FillBlankMark;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface FillBlankMarkRepository extends JpaRepository<FillBlankMark,Long> {
    List<FillBlankMark> findByRuleId(Long ruleId);
}
