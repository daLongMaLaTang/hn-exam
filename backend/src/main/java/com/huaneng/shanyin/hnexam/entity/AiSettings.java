package com.huaneng.shanyin.hnexam.entity;
import jakarta.persistence.*; import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
public class AiSettings { @Id private Long id=1L; private boolean enabled; @Column(length=500) private String endpoint=""; private String model=""; private int timeoutSeconds=30; @Column(length=2048) private String apiKeyCiphertext; }
