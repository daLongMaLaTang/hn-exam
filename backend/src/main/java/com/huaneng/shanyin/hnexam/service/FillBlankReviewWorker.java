package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.repository.ExamAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component @RequiredArgsConstructor
public class FillBlankReviewWorker {
    private final ExamAttemptRepository attempts;
    private final ExamService exams;
    private final FillBlankSemanticService semantic;
    private final OpenAiCompatibleGradingProvider provider;
    private final TransactionTemplate tx;

    @Scheduled(fixedDelayString="${app.exams.fill-review-ms:5000}")
    public void reviewPending() {
        if (!provider.available()) return;
        var ids = tx.execute(s -> attempts.findByStatus(AttemptStatus.SUBMITTED).stream()
                .filter(a -> a.getExam().getStatus() != ExamStatus.CANCELLED)
                .filter(a -> a.getAnswers().stream().anyMatch(v -> "FILL_PENDING".equals(v.getAiStatus())))
                .limit(20).map(a -> a.getId()).toList());
        if (ids == null) return;
        for (Long id : ids) {
            try {
                var review = exams.review(id, null);
                for (var q : review.questions()) {
                    if (!"FILL_PENDING".equals(q.aiStatus()) || q.awardedPoints() != null) continue;
                    try {
                        var result = semantic.grade(id, q.id(), q.stem(), q.correctAnswer(), q.savedAnswer(), q.points(), review.grade().userId());
                        exams.resolveFillBlank(id, q.id(), result, provider.config().getModel());
                    } catch (RuntimeException e) { exams.resolveFillBlank(id, q.id(), null, null); }
                }
            } catch (RuntimeException ignored) { /* Cancelled or concurrently reviewed; next run can continue other papers. */ }
        }
    }
}
