package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.entity.ExamReminder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface ExamReminderRepository extends JpaRepository<ExamReminder, Long> {
    List<ExamReminder> findByExamId(Long examId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExamReminder r where r.exam.id = :examId order by r.id")
    List<ExamReminder> findLockedByExamId(Long examId);
    Page<ExamReminder> findByUserIdOrderBySentAtDescIdDesc(Long userId, Pageable pageable);
    long countByUserIdAndReadAtIsNull(Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExamReminder r where r.id = :id and r.user.id = :userId")
    Optional<ExamReminder> findOwnedLocked(Long id, Long userId);
}
