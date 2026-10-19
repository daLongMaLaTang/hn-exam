package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name="fill_blank_decision") @Getter @Setter
public class FillBlankDecision {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long examId;
    private Long questionId;
    @Column(length=64) private String questionVersion;
    private int slot;
    @Lob private String stem;
    @Lob private String submitted;
    private boolean correct;
    private int affectedCount;
    private Long ruleId;
    @Lob private String targetAnswerIds;
    private String actor;
    private Instant createdAt;
    private Instant revokedAt;
    private String revokedBy;
}
