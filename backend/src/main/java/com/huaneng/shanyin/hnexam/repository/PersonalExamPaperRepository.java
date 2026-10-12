package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.entity.PersonalExamPaper;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalExamPaperRepository extends JpaRepository<PersonalExamPaper, Long> {
    Optional<PersonalExamPaper> findByExamIdAndUserId(Long examId, Long userId);
    List<PersonalExamPaper> findByExamId(Long examId);
    boolean existsByExamIdAndUserId(Long examId, Long userId);
}
