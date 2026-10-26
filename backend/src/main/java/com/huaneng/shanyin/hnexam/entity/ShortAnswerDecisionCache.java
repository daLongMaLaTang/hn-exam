package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Getter @Setter @NoArgsConstructor
@Table(name="short_answer_decision_cache")
public class ShortAnswerDecisionCache {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,unique=true,length=64) private String cacheKey;
    private Long rubricId;
    @Column(length=64) private String modelConfig;
    @Lob @Column(length=org.hibernate.Length.LONG32) private String answerText;
    @Lob @Column(length=org.hibernate.Length.LONG32) private String decisionJson;
    private String model;
    @Column(length=20) private String state="PREPARING";
    private String token;
    private Instant leaseUntil;
    private Instant createdAt=Instant.now();
    private Instant invalidatedAt;
}
