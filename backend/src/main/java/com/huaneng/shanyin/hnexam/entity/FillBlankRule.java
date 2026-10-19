package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name="fill_blank_rule", uniqueConstraints=@UniqueConstraint(columnNames={"questionId","questionVersion","slot","answerHash"}))
@Getter @Setter
public class FillBlankRule {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long questionId;
    @Column(length=64) private String questionVersion;
    private int slot;
    @Column(length=64) private String answerHash;
    @Lob private String acceptedAnswer;
    @Lob private String stem;
    private boolean active;
    private String createdBy;
    private Instant createdAt;
    private String disabledBy;
    private Instant disabledAt;
}
