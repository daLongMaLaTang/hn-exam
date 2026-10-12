package com.huaneng.shanyin.hnexam.repository;
import com.huaneng.shanyin.hnexam.entity.ExternalGrade;import java.util.*;
public interface ExternalGradeRepository extends org.springframework.data.jpa.repository.JpaRepository<ExternalGrade,Long> { List<ExternalGrade> findByUserIdOrderByExamDateDesc(Long userId); Optional<ExternalGrade> findByRequestId(String requestId); }
