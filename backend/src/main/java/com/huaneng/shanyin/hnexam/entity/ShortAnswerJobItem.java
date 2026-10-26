package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Getter @Setter @NoArgsConstructor
@Table(name="short_answer_job_item",
    uniqueConstraints=@UniqueConstraint(name="uk_short_job_target",columnNames={"jobId","targetKey"}),
    indexes=@Index(name="idx_short_item_due",columnList="state,nextRunAt"))
public class ShortAnswerJobItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long jobId;
    @Column(nullable=false,length=100) private String targetKey;
    @Column(nullable=false,length=20) private String kind;
    private Long snapshotId;
    private boolean personal;
    private Long attemptId;
    private Long questionId;
    private Long expectedRevision;
    @Column(length=64) private String answerVersion;
    @Column(nullable=false,length=20) private String state="QUEUED";
    private int attempts;
    private Instant nextRunAt=Instant.now();
    private String token;
    private Instant leaseUntil;
    private Long rubricId;
    @Column(length=64) private String configVersion;
    @Column(length=40) private String errorCode;
    @Column(length=1000) private String errorMessage;
}
