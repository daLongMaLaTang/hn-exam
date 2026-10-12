package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name="sys_audit_event") @Getter @Setter
public class AuditEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Instant occurredAt = Instant.now();
    @Column(length=100) private String actor;
    @Column(length=10) private String method;
    @Column(length=500) private String path;
    private int responseStatus;
}
