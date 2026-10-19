package com.huaneng.shanyin.hnexam.dto;

import com.huaneng.shanyin.hnexam.domain.ExamStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ExamTrackingDtos {
    private ExamTrackingDtos() {}
    public record Person(Long userId, String employeeNo, String name, String department, String professionalDirection,
                         boolean active, String state, Long attemptId, int attemptCount, Integer score, Boolean passed,
                         Instant startedAt, Instant submittedAt, Instant remindedAt, Instant readAt, boolean canRemind) {}
    public record Tracking(Long examId, String title, ExamStatus examStatus, Instant startTime, Instant endTime,
                           Long makeupSourceExamId, boolean frozen, Instant generatedAt, Map<String, Long> counts, List<Person> people) {}
    public record ReminderRequest(@NotEmpty @Size(max = 500) List<@NotNull @Positive Long> userIds) {}
    public record ReminderResult(int sent, int skipped) {}
    public record Notice(Long id, Long examId, String title, Instant startTime, Instant endTime, ExamStatus examStatus,
                         Instant sentAt, Instant readAt, boolean canOpen) {}
    public record Inbox(List<Notice> items, long unreadCount, long total, int page, int totalPages) {}
}
