package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "practice_record")
@Getter
@Setter
@NoArgsConstructor
public class PracticeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Lob
    private String answerText;

    @Column(nullable = false)
    private boolean correct;

    // Null means a legacy objective record; false means subjective self-review without a verdict.
    private Boolean assessed;

    @Column(nullable = false)
    private Instant answeredAt;
}
