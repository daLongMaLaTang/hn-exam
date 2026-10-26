package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.repository.ExamAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ExamExpiryScheduler {
    private final ExamAttemptRepository attempts;
    private final ExamService exams;
    @Value("${app.exams.expiry-batch-size:100}") private int batchSize;
    @Value("${app.exams.expiry-max-batches:20}") private int maxBatches;
    @Value("${app.exams.expiry-run-budget-ms:5000}") private long runBudgetMs;
    private long afterId;

    // Deliberately no surrounding transaction: a failure rolls back only one paper.
    @Scheduled(fixedDelayString = "${app.exams.expiry-check-ms:15000}", initialDelayString = "${app.exams.expiry-check-ms:15000}")
    public synchronized void autoSubmitExpired() {
        long started = System.nanoTime();
        Instant cutoff = Instant.now().minusSeconds(exams.submissionGraceSeconds());
        int size = Math.max(1, Math.min(batchSize, 500));
        for (int page = 0; page < Math.max(1, maxBatches); page++) {
            var ids = attempts.findExpiryCandidateIds(cutoff, afterId, PageRequest.of(0, size));
            if (ids.isEmpty()) { afterId = 0; return; }
            for (Long id : ids) {
                try {
                    exams.expireAttempt(id);
                } catch (RuntimeException failure) {
                    log.warn("Automatic submission failed for attempt {}; will retry next sweep", id, failure);
                }
                // Continue after a bad/locked row; bounded runs resume here instead of starving later rows.
                afterId = id;
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= Math.max(1, runBudgetMs)) return;
            }
            if (ids.size() < size) { afterId = 0; return; }
        }
    }
}
