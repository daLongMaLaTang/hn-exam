package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One decision per submitted blank; nullable correct means awaiting a human decision. */
@Entity @Table(name="fill_blank_mark", uniqueConstraints=@UniqueConstraint(columnNames={"answer_id","slot"}))
@Getter @Setter
public class FillBlankMark {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="answer_id") private AttemptAnswer answer;
    private int slot;
    @Lob private String submitted;
    @Column(length=64) private String questionVersion;
    private Boolean correct;
    private Long ruleId;
    private Long decisionId;
    @Column(length=24) private String source;
}
