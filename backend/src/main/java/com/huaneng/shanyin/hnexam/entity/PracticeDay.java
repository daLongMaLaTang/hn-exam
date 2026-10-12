package com.huaneng.shanyin.hnexam.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDate;
@Entity @Getter @Setter @NoArgsConstructor @Table(uniqueConstraints=@UniqueConstraint(columnNames={"user_id","practice_date"}))
public class PracticeDay { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id") private AppUser user; @Column(name="practice_date") private LocalDate date; private long seconds; }
