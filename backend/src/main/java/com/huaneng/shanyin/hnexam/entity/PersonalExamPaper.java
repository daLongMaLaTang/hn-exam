package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "personal_exam_paper", uniqueConstraints = @UniqueConstraint(
        name = "uk_personal_paper_exam_user", columnNames = {"exam_id", "user_id"}))
@Getter @Setter @NoArgsConstructor
public class PersonalExamPaper {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "paper", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<PersonalExamQuestion> questions = new ArrayList<>();
}
