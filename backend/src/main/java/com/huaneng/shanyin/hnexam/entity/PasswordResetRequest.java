package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(name="password_reset_request", indexes=@Index(name="idx_reset_user_state",columnList="user_id,state"))
public class PasswordResetRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) @JoinColumn(name="user_id") private AppUser user;
    @Column(nullable=false,length=12) private String state="PENDING";
    @Column(nullable=false) private Instant requestedAt=Instant.now();
    private Instant reviewedAt;
    private String reviewedBy;
    private long authVersion;
}
