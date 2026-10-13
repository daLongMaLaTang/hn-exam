package com.huaneng.shanyin.hnexam.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

/** Daily counters and a permanent mutex row; contains no prompts or credentials. */
@Entity @Getter @Setter @NoArgsConstructor
public class AiUsageBucket {
    @Id @Column(length=64) private String id;
    private LocalDate usageDate;
    private long admitted;
    private long succeeded;
    private long failed;
    private long timedOut;
    private long abandoned;
    private long rejected;
    private long promptTokens;
    private long completionTokens;
    private long missingUsage;
    private long durationMillis;
    public AiUsageBucket(String id, LocalDate date) { this.id=id; this.usageDate=date; }
}
