package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** One inbox entry per exam and recipient; subsequent reminders refresh it after a cooldown. */
@Entity
@Table(name = "exam_reminder", uniqueConstraints = @UniqueConstraint(name = "uk_exam_reminder_recipient", columnNames = {"exam_id", "user_id"}),
        indexes = @Index(name = "idx_reminder_user_sent", columnList = "user_id,sent_at"))
@Getter @Setter
public class ExamReminder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "exam_id", nullable = false) private Exam exam;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private AppUser user;
    @Column(nullable = false) private Instant sentAt;
    private Instant readAt;
    @Column(nullable = false, length = 100) private String sentBy;
    @Column(nullable = false) private int sendCount;
}
