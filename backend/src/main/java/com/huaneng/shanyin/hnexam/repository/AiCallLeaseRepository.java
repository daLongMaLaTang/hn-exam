package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.entity.AiCallLease;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface AiCallLeaseRepository extends JpaRepository<AiCallLease,String> {
    List<AiCallLease> findByExpiresAtLessThanEqual(Instant now);
    long countByUserId(Long userId);
}
