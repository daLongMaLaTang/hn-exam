package com.huaneng.shanyin.hnexam.entity;

import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "qb_question")
@Getter
@Setter
@NoArgsConstructor
public class Question {
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean deleted;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionType type;

    @Lob
    @Column(nullable = false, length = org.hibernate.Length.LONG32)
    private String stem;

    @Lob
    @Column(nullable = false, length = org.hibernate.Length.LONG32)
    private String answer;

    @Lob
    @Column(name = "options_json", length = org.hibernate.Length.LONG32)
    private String optionsJson;

    @Lob
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionStatus status = QuestionStatus.PENDING_REVIEW;

    @Column(name = "course_code", nullable = false, length = 64)
    private String courseCode;

    @Column(length = 100)
    private String chapter = "未分章";

    @Column(length = 200)
    private String source;

    @Column(nullable = false)
    private int difficulty = 3;
}

