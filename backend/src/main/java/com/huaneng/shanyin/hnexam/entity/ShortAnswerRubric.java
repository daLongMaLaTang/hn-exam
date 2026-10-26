package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Confirmed rows are immutable; edits create a new draft with a new identity. */
@Entity @Getter @Setter @NoArgsConstructor
@Table(name="short_answer_rubric", indexes=@Index(name="idx_rubric_question_version", columnList="question_id,question_version"))
public class ShortAnswerRubric {
    @Column(unique=true,length=64) private String reuseKey;
    private String policyVersion;
    private String normalizationVersion;
    private Integer formatVersion;
    private String preparationToken;
    private Instant preparationLeaseUntil;
    private Instant validatedAt;
    @Column(length=1000) private String preparationError;
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long questionId;
    @Column(nullable=false,length=64) private String questionVersion;
    @Lob @Column(length=org.hibernate.Length.LONG32) private String stem;
    @Lob @Column(length=org.hibernate.Length.LONG32) private String referenceAnswer;
    @Lob @Column(length=org.hibernate.Length.LONG32) private String rubricJson;
    @Column(nullable=false,length=20) private String status="DRAFT";
    private String createdBy;
    private Instant createdAt;
    private String confirmedBy;
    private Instant confirmedAt;
}
