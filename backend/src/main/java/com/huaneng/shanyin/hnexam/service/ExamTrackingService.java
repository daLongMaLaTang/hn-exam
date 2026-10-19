package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamTrackingDtos.*;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class ExamTrackingService {
    private final ExamRepository exams;
    private final ExamAttemptRepository attempts;
    private final PersonalExamPaperRepository papers;
    private final UserRepository users;
    private final ExamReminderRepository reminders;
    private final PrivateAdministratorPolicy privateAdmins;
    private static final Duration REMINDER_COOLDOWN = Duration.ofHours(24);

    @Transactional(readOnly = true)
    public Tracking tracking(Long id) { return build(exams.findById(id).orElseThrow(() -> new IllegalArgumentException("考试不存在")), Instant.now()); }

    private Tracking build(Exam exam, Instant now) {
        if (exam.getStatus() == ExamStatus.DRAFT) throw new IllegalStateException("发布考试后可查看应考追踪");
        var allAttempts = attempts.findByExamIdOrderByStartedAtDesc(exam.getId());
        // IDs break equal timestamps deterministically; cancelled attempts still count as participation.
        var byUser = allAttempts.stream().sorted(Comparator.comparing(ExamAttempt::getId).reversed())
                .collect(Collectors.groupingBy(a -> a.getUser().getId(), LinkedHashMap::new, Collectors.toList()));
        boolean frozen = exam.isAudienceFrozen() || exam.isWrongQuestionPaper();
        Set<Long> ids = new LinkedHashSet<>();
        if (exam.isWrongQuestionPaper()) papers.findByExamId(exam.getId()).forEach(p -> ids.add(p.getUser().getId()));
        else if (exam.isAudienceFrozen() || "USERS".equals(exam.getAudienceMode())) ids.addAll(exam.getAudienceUserIds());
        else users.findAll().stream().filter(this::activeCandidate)
                .filter(u -> !"MAJORS".equals(exam.getAudienceMode()) || exam.getAudienceMajors().contains(u.getProfessionalDirection()))
                .forEach(u -> ids.add(u.getId()));
        // Legacy exams have no reliable publish-time roster. Preserve known participants, including disabled accounts.
        if (!frozen) ids.addAll(byUser.keySet());
        var reminderMap = reminders.findByExamId(exam.getId()).stream().collect(Collectors.toMap(r -> r.getUser().getId(), r -> r));
        var people = users.findAllById(ids).stream().filter(privateAdmins::visible)
                .sorted(Comparator.comparing(AppUser::getEmployeeNo)).map(u -> {
                    var history = byUser.getOrDefault(u.getId(), List.of());
                    ExamAttempt latest = history.isEmpty() ? null : history.get(0);
                    String state = state(exam, latest, now, frozen);
                    ExamReminder reminder = reminderMap.get(u.getId());
                    boolean canRemind = state.equals("NOT_STARTED") && activeCandidate(u) && exam.getStatus() == ExamStatus.PUBLISHED
                            && (reminder == null || !now.isBefore(reminder.getSentAt().plus(REMINDER_COOLDOWN)));
                    return new Person(u.getId(), u.getEmployeeNo(), u.getName(), u.getDepartment(), u.getProfessionalDirection(),
                            activeCandidate(u), state, latest == null ? null : latest.getId(), history.size(),
                            latest == null || latest.getStatus() != AttemptStatus.GRADED ? null : latest.getScore(),
                            latest == null || latest.getStatus() != AttemptStatus.GRADED ? null : latest.getPassed(),
                            latest == null ? null : latest.getStartedAt(), latest == null ? null : latest.getSubmittedAt(),
                            reminder == null ? null : reminder.getSentAt(), reminder == null ? null : reminder.getReadAt(), canRemind);
                }).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : List.of("NOT_STARTED", "IN_PROGRESS", "PROCESSING", "SUBMITTED", "GRADED", "ABSENT", "UNCONFIRMED", "CANCELLED"))
            counts.put(state, people.stream().filter(p -> p.state().equals(state)).count());
        counts.put("TOTAL", (long) people.size());
        counts.put("PASSED", people.stream().filter(p -> p.state().equals("GRADED") && Boolean.TRUE.equals(p.passed())).count());
        counts.put("FAILED", people.stream().filter(p -> p.state().equals("GRADED") && Boolean.FALSE.equals(p.passed())).count());
        return new Tracking(exam.getId(), exam.getTitle(), exam.getStatus(), exam.getStartTime(), exam.getEndTime(),
                exam.getMakeupSourceExamId(), frozen, now, counts, people);
    }

    private String state(Exam exam, ExamAttempt latest, Instant now, boolean frozen) {
        if (exam.getStatus() == ExamStatus.CANCELLED) return "CANCELLED";
        boolean ended = exam.getStatus() == ExamStatus.CLOSED || exam.getEndTime() != null && !now.isBefore(exam.getEndTime());
        if (latest == null) return ended ? frozen ? "ABSENT" : "UNCONFIRMED" : "NOT_STARTED";
        if (latest.getStatus() == AttemptStatus.IN_PROGRESS) {
            Instant deadline = latest.getDeadline() != null ? latest.getDeadline() : latest.getStartedAt().plus(Duration.ofMinutes(exam.getDurationMinutes()));
            return ended || !now.isBefore(deadline) ? "PROCESSING" : "IN_PROGRESS";
        }
        return latest.getStatus().name();
    }

    private boolean activeCandidate(AppUser u) {
        return privateAdmins.visible(u) && u.getStatus() == UserStatus.ACTIVE && u.getRoles().stream().anyMatch(r -> "CANDIDATE".equals(r.effectiveAuthority()));
    }

    @Transactional
    public ReminderResult remind(Long examId, List<Long> userIds, String actor) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > 500 || userIds.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请选择1至500位学员");
        // Serialize sends with publish/close/cancel and other sends for this exam; unique key is a second guard.
        var exam = exams.findLockedById(examId).orElseThrow(() -> new IllegalArgumentException("考试不存在"));
        Instant now = Instant.now();
        if (exam.getStatus() != ExamStatus.PUBLISHED || exam.getEndTime() != null && !now.isBefore(exam.getEndTime()))
            throw new IllegalStateException("考试已截止或不可参加，无法发送提醒");
        var existing = reminders.findLockedByExamId(examId).stream().collect(Collectors.toMap(r -> r.getUser().getId(), r -> r));
        var eligible = build(exam, now).people().stream().filter(Person::canRemind).map(Person::userId).collect(Collectors.toSet());
        var requested = new LinkedHashSet<>(userIds);
        int sent = 0;
        for (Long id : requested) {
            if (!eligible.contains(id)) continue;
            var reminder = existing.getOrDefault(id, new ExamReminder());
            reminder.setExam(exam); reminder.setUser(users.getReferenceById(id));
            reminder.setSentAt(now); reminder.setReadAt(null); reminder.setSentBy(privateAdmins.actorLabel(actor));
            reminder.setSendCount(reminder.getSendCount() + 1); reminders.save(reminder); sent++;
        }
        return new ReminderResult(sent, requested.size() - sent);
    }

    @Transactional(readOnly = true)
    public Inbox inbox(String employeeNo, int page) {
        var user = users.findByEmployeeNo(employeeNo).orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        var entries = reminders.findByUserIdOrderBySentAtDescIdDesc(user.getId(), PageRequest.of(Math.max(0, page), 20));
        return new Inbox(entries.map(r -> notice(r, user, Instant.now())).getContent(), reminders.countByUserIdAndReadAtIsNull(user.getId()),
                entries.getTotalElements(), entries.getNumber(), entries.getTotalPages());
    }

    private Notice notice(ExamReminder r, AppUser user, Instant now) {
        Exam e = r.getExam();
        boolean allowed = (!e.isAudienceFrozen() && (e.getAudienceMode() == null || "ALL".equals(e.getAudienceMode()))) || e.getAudienceUserIds().contains(user.getId());
        boolean canOpen = activeCandidate(user) && allowed && e.getStatus() == ExamStatus.PUBLISHED && (e.getEndTime() == null || now.isBefore(e.getEndTime()))
                && (!e.isWrongQuestionPaper() || papers.existsByExamIdAndUserId(e.getId(), user.getId()));
        return new Notice(r.getId(), e.getId(), e.getTitle(), e.getStartTime(), e.getEndTime(), e.getStatus(), r.getSentAt(), r.getReadAt(), canOpen);
    }

    @Transactional
    public Notice read(Long id, String employeeNo) {
        var user = users.findByEmployeeNo(employeeNo).orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        var reminder = reminders.findOwnedLocked(id, user.getId()).orElseThrow(() -> new IllegalArgumentException("通知不存在"));
        if (reminder.getReadAt() == null) reminder.setReadAt(Instant.now());
        return notice(reminder, user, Instant.now());
    }
}
