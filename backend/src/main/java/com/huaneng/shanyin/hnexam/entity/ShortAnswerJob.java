package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Getter @Setter @NoArgsConstructor
@Table(name="short_answer_job",indexes=@Index(name="idx_short_job_exam",columnList="examId,id"))
public class ShortAnswerJob {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long examId;
    @Column(nullable=false,length=20) private String kind;
    @Column(nullable=false,length=20) private String state="QUEUED";
    @Column(unique=true,length=100) private String activeKey;
    private Long userId;
    private String actor;
    private Instant createdAt=Instant.now();
    private Instant finishedAt;
}
