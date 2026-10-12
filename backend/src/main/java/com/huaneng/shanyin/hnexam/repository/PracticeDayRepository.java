package com.huaneng.shanyin.hnexam.repository;
import com.huaneng.shanyin.hnexam.entity.PracticeDay; import java.time.LocalDate; import java.util.*;
public interface PracticeDayRepository extends org.springframework.data.jpa.repository.JpaRepository<PracticeDay,Long> { Optional<PracticeDay> findByUserIdAndDate(Long userId, LocalDate date); List<PracticeDay> findByUserIdOrderByDateDesc(Long userId); }
