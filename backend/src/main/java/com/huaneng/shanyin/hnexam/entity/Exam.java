package com.huaneng.shanyin.hnexam.entity;

import com.huaneng.shanyin.hnexam.domain.ExamStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exam")
@Getter
@Setter
@NoArgsConstructor
public class Exam {
    private Long makeupSourceExamId;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "course_code", length = 100)
    private String courseCode;

    @Lob
    private String chaptersJson;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean wrongQuestionPaper;

    @Column(nullable = false, columnDefinition = "integer default 20")
    private int wrongQuestionLimit = 20;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean allWrongQuestions;
    @Column(length = 20)
    private String audienceMode = "ALL";
    @ElementCollection
    @CollectionTable(name = "exam_audience_user", joinColumns = @JoinColumn(name = "exam_id"))
    @Column(name = "user_id")
    private java.util.Set<Long> audienceUserIds = new java.util.LinkedHashSet<>();
    @ElementCollection
    @CollectionTable(name = "exam_audience_major", joinColumns = @JoinColumn(name = "exam_id"))
    @Column(name = "major", length = 100)
    private java.util.Set<String> audienceMajors = new java.util.LinkedHashSet<>();
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean audienceFrozen;

    @Lob
    private String description;

    @Column(nullable = false)
    private int durationMinutes = 90;

    @Column(nullable = false)
    private int totalScore = 100;

    @Column(nullable = false)
    private int passScore = 90;

    private String cancellationReason;
    private Instant cancelledAt;
    private String cancelledBy;

    private Instant startTime;
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ExamStatus status = ExamStatus.DRAFT;

    @Column(nullable = false)
    private boolean allowRetake;

    @Column(nullable = false)
    private int maxAttempts = 1;

    @Column(nullable = false)
    private boolean shuffleQuestions = true;

    @Column(nullable = false)
    private boolean basicAntiCheat = true;

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<ExamQuestion> questions = new ArrayList<>();

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PersonalExamPaper> personalPapers = new ArrayList<>();
}
