package com.huaneng.shanyin.hnexam.entity;

import com.huaneng.shanyin.hnexam.domain.UserStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "sys_user", uniqueConstraints = @UniqueConstraint(name = "uk_user_employee_no", columnNames = "employee_no"))
@Getter
@Setter
@NoArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_no", nullable = false, length = 32)
    private String employeeNo;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 32)
    private String phone;
    private String practiceSession;
    private java.time.Instant practiceHeartbeat;
    private java.time.Instant practiceActiveUntil;
    private Boolean practiceActive;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = true;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int authVersion;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean privateAdministrator;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(length = 100)
    private String department;

    @Column(length = 100)
    private String team;

    @Column(name = "professional_direction", length = 100)
    private String professionalDirection;

    @Column(name = "education_major", length = 100)
    private String educationMajor;

    @Column(length = 100)
    private String position;

    @Column(name = "training_start_date")
    private LocalDate trainingStartDate;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_user_role", columnNames = {"user_id", "role_id"}))
    private Set<Role> roles = new LinkedHashSet<>();
}

