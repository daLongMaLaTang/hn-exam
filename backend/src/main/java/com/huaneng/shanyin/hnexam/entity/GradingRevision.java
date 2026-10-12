package com.huaneng.shanyin.hnexam.entity;
import jakarta.persistence.*;import lombok.*;import java.time.Instant;
@Entity @Getter @Setter @NoArgsConstructor public class GradingRevision { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; private Long attemptId; private String actor; private Instant recordedAt; @Lob private String scoresJson; @Lob private String comment; }
