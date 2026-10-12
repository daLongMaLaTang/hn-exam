package com.huaneng.shanyin.hnexam.entity;
import jakarta.persistence.*; import lombok.*; import java.time.*;
@Entity @Getter @Setter @NoArgsConstructor
public class ExternalGrade { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(fetch=FetchType.LAZY) private AppUser user; private String title; private String course; private int score; private int totalScore; private int passScore; private LocalDate examDate; @Column(length=1000) private String note; private String recordedBy; private Instant recordedAt; @Column(unique=true,length=64) private String requestId; }
