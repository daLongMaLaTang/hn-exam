package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sys_role", uniqueConstraints = @UniqueConstraint(name = "uk_role_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean builtIn;

    @Column(length = 64)
    private String permissionGroup;

    public String effectiveAuthority() {
        return builtIn || permissionGroup == null ? code : permissionGroup;
    }

    public Role(String code, String name, boolean builtIn) {
        this.code = code;
        this.name = name;
        this.builtIn = builtIn;
    }
}

