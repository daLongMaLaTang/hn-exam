package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.entity.AiUsageBucket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.time.LocalDate;
import java.util.Optional;

public interface AiUsageBucketRepository extends JpaRepository<AiUsageBucket,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AiUsageBucket b where b.id = 'LOCK'")
    Optional<AiUsageBucket> lockLedger();
    @Modifying @Query("delete from AiUsageBucket b where b.usageDate < :cutoff")
    void deleteBefore(LocalDate cutoff);
}
