package com.huaneng.shanyin.hnexam.repository;
import com.huaneng.shanyin.hnexam.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AuditEventRepository extends JpaRepository<AuditEvent,Long> {
    List<AuditEvent> findTop200ByOrderByOccurredAtDesc();
}
