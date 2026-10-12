package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.domain.ExamStatus;
import com.huaneng.shanyin.hnexam.entity.Exam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    List<Exam> findAllByOrderByIdDesc();
    List<Exam> findByStatusOrderByStartTimeDesc(ExamStatus status);
    long countByStatus(ExamStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exam e where e.id = :id")
    Optional<Exam> findLockedById(Long id);
}
