package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    long countByDeletedFalse();
    long countByStatus(QuestionStatus status);
    long countByType(QuestionType type);
    List<Question> findByStatusOrderByIdDesc(QuestionStatus status);
    long countBySourceStartingWith(String sourcePrefix);
    List<Question> findBySourceStartingWith(String sourcePrefix);
}
