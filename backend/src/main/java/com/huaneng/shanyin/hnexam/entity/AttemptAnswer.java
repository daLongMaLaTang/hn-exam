package com.huaneng.shanyin.hnexam.entity;

import com.huaneng.shanyin.hnexam.domain.GradingMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "exam_attempt_answer", uniqueConstraints = @UniqueConstraint(
        name = "uk_attempt_question", columnNames = {"attempt_id", "question_id"}))
@Getter
@Setter
@NoArgsConstructor
public class AttemptAnswer {
    private Long shortRubricId;
    private String shortReviewState;
    @Column(nullable=false,columnDefinition="boolean default false") private boolean shortSampleApproved;
    @Column(nullable=false,columnDefinition="boolean default false") private boolean shortSampleDisagreed;
    private String shortRunToken;
    private java.time.Instant shortRunStartedAt;
    @Column(nullable=false,columnDefinition="bigint default 0")
    private long shortRevision;
    @Lob @Column(length=org.hibernate.Length.LONG32)
    private String shortEvaluationJson;
    @Lob @Column(length=org.hibernate.Length.LONG32)
    private String shortRisksJson;
    @OneToMany(mappedBy="answer", cascade=CascadeType.ALL, orphanRemoval=true)
    @OrderBy("slot asc")
    private java.util.List<FillBlankMark> blankMarks = new java.util.ArrayList<>();
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private ExamAttempt attempt;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.JOIN)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Lob
    private String answerText;

    private Double semanticSimilarity;
    private String similaritySource;
    private Double aiSuggestedSimilarity;
    private String aiModel;
    private String aiStatus;
    private Boolean correct;
    private Integer awardedPoints;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GradingMethod gradingMethod;

    @Lob
    private String gradingComment;

    // Reserved for the later AI-assisted grading phase. AI suggestions never become final scores directly.
    private Integer aiSuggestedPoints;

    @Lob
    private String aiSuggestionReason;
}
