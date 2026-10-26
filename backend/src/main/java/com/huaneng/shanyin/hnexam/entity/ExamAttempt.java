package com.huaneng.shanyin.hnexam.entity;

import com.huaneng.shanyin.hnexam.domain.AttemptStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exam_attempt", indexes = {
        @Index(name = "idx_attempt_exam_user", columnList = "exam_id,user_id"),
        @Index(name = "idx_attempt_user_status", columnList = "user_id,status"),
        @Index(name = "idx_attempt_status_deadline", columnList = "status,deadline")
})
@Getter
@Setter
@NoArgsConstructor
public class ExamAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "personal_paper_id")
    private PersonalExamPaper personalPaper;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant submittedAt;
    private Instant deadline;
    private Integer score;
    private Boolean passed;
    private Instant gradedAt;

    @Column(length = 100)
    private String gradedByEmployeeNo;

    @Lob
    private String gradingNote;

    @Column(nullable = false)
    private int focusLossCount;

    @Column(nullable = false)
    private long answerRevision;

    @Column(length = 64)
    private String lastAnswerRequestId;

    @Column(length = 64)
    private String lastAnswerRequestHash;

    @OneToMany(mappedBy = "attempt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AttemptAnswer> answers = new ArrayList<>();
}
