package com.huaneng.shanyin.hnexam.entity;

import com.huaneng.shanyin.hnexam.domain.QuestionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "personal_exam_question", uniqueConstraints = @UniqueConstraint(
        name = "uk_personal_paper_question", columnNames = {"paper_id", "question_id"}))
@Getter @Setter @NoArgsConstructor
public class PersonalExamQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_id", nullable = false)
    private PersonalExamPaper paper;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.JOIN)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(nullable = false) private int points;
    @Column(nullable = false) private int position;
    @Enumerated(EnumType.STRING) private QuestionType snapshotType;
    @Lob private String snapshotStem;
    @Lob private String snapshotOptionsJson;
    @Lob private String snapshotAnswer;
    @Lob private String snapshotExplanation;

    public void captureSnapshot() {
        snapshotType = question.getType(); snapshotStem = question.getStem();
        snapshotOptionsJson = question.getOptionsJson(); snapshotAnswer = question.getAnswer();
        snapshotExplanation = question.getExplanation();
    }
}
