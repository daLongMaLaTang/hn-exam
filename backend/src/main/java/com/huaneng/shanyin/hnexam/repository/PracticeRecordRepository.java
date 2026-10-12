package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.entity.PracticeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PracticeRecordRepository extends JpaRepository<PracticeRecord, Long> {
    List<PracticeRecord> findByUserIdAndCorrectFalseOrderByAnsweredAtDesc(Long userId);
    List<PracticeRecord> findByUserIdOrderByAnsweredAtDesc(Long userId);
    long countByUserId(Long userId);
    long countByUserIdAndCorrectTrue(Long userId);
}
