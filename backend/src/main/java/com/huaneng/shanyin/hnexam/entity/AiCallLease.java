package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity @Getter @Setter @NoArgsConstructor
@Table(indexes={@Index(name="idx_ai_lease_expiry", columnList="expiresAt")})
public class AiCallLease {
    @Id @Column(length=36) private String id;
    @Column(nullable=false) private Long userId;
    @Column(nullable=false) private LocalDate usageDate;
    @Column(nullable=false) private Instant expiresAt;
}
