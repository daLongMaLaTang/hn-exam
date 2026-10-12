package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.domain.AttemptStatus;
import com.huaneng.shanyin.hnexam.domain.ExamStatus;
import com.huaneng.shanyin.hnexam.entity.ExamAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import java.time.Instant;

import java.util.List;
import java.util.Optional;

public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {
    interface Participation { Long getExamId(); long getParticipants(); }
    interface TrendGrade { Long getExamId(); long getGraded(); double getAverage(); long getPassed(); }
    @Query("select a.exam.id as examId, count(distinct a.user.id) as participants from ExamAttempt a where a.status <> com.huaneng.shanyin.hnexam.domain.AttemptStatus.CANCELLED and a.exam.status <> com.huaneng.shanyin.hnexam.domain.ExamStatus.CANCELLED group by a.exam.id")
    List<Participation> trendParticipants();
    @Query("""
            select a.exam.id as examId, count(a) as graded, avg(a.score * 100.0 / a.exam.totalScore) as average,
                   sum(case when a.passed = true then 1 else 0 end) as passed
            from ExamAttempt a where a.status = com.huaneng.shanyin.hnexam.domain.AttemptStatus.GRADED
            and a.exam.status <> com.huaneng.shanyin.hnexam.domain.ExamStatus.CANCELLED
            and not exists (select b.id from ExamAttempt b where b.exam.id = a.exam.id and b.user.id = a.user.id
                and b.status = com.huaneng.shanyin.hnexam.domain.AttemptStatus.GRADED and b.id > a.id)
            group by a.exam.id
            """)
    List<TrendGrade> trendGrades();
    List<ExamAttempt> findByUserIdOrderByStartedAtDesc(Long userId);
    List<ExamAttempt> findByExamIdOrderByStartedAtDesc(Long examId);
    Optional<ExamAttempt> findByIdAndUserId(Long id, Long userId);
    long countByExamIdAndUserId(Long examId, Long userId);
    long countByStatus(AttemptStatus status);
    long countByStatusAndExamStatusNot(AttemptStatus status, com.huaneng.shanyin.hnexam.domain.ExamStatus examStatus);
    long countByUserIdAndStatusAndExamStatusNot(Long userId, AttemptStatus status, ExamStatus examStatus);
    // Dashboard counts must not load detached attempts or traverse lazy exam references.
    @Query("""
            select count(distinct a.exam.id) from ExamAttempt a
            where a.user.id = :userId and a.passed = true and a.exam.status <> :excludedStatus
            """)
    long countPassedExamsByUserId(Long userId, ExamStatus excludedStatus);
    List<ExamAttempt> findByExamIdAndUserIdOrderByStartedAtDesc(Long examId, Long userId);
    List<ExamAttempt> findByUserIdAndStatus(Long userId, AttemptStatus status);
    List<ExamAttempt> findByStatus(AttemptStatus status);
    // Keyset pagination: completed rows disappear, so offset pagination would skip work.
    // Null deadlines are legacy rows; the worker checks their calculated deadline without locking live exams.
    @Query("""
            select a.id from ExamAttempt a
            where a.status = com.huaneng.shanyin.hnexam.domain.AttemptStatus.IN_PROGRESS
              and a.id > :afterId
              and (a.deadline <= :now or a.deadline is null
                   or a.exam.status = com.huaneng.shanyin.hnexam.domain.ExamStatus.CLOSED)
            order by a.id
            """)
    List<Long> findExpiryCandidateIds(Instant now, Long afterId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExamAttempt a where a.id = :id")
    Optional<ExamAttempt> findLockedById(Long id);
}
