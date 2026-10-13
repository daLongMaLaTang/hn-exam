package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.QuestionDtos;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Objects;

/** Read a snapshot, assess without a DB connection, then revalidate before saving feedback. */
@Service @RequiredArgsConstructor
public class PracticeSubmissionService {
    private final QuestionRepository questions;
    private final UserRepository users;
    private final ExamAttemptRepository attempts;
    private final PracticeRecordRepository practices;
    private final AnswerGrader grader;
    private final FillBlankSemanticService semantic;
    private final TransactionTemplate transactions;

    private record Snapshot(QuestionType type, String stem, String answer, Long userId) {}

    @Transactional(propagation = Propagation.NEVER)
    public QuestionDtos.PracticeResult submit(Long questionId, String answer, String employeeNo) {
        Snapshot snapshot = transactions.execute(status -> {
            AppUser user = users.findByEmployeeNo(employeeNo).orElseThrow();
            Question q = eligible(questionId, user);
            return new Snapshot(q.getType(), q.getStem(), q.getAnswer(), user.getId());
        });
        boolean assessed = grader.isObjective(snapshot.type());
        boolean correct = assessed && grader.isCorrect(snapshot.type(), snapshot.answer(), answer);
        if (snapshot.type() == QuestionType.FILL_BLANK) {
            try { correct = semantic.grade(null, questionId, snapshot.stem(), snapshot.answer(), answer, 1, snapshot.userId()).correct(); }
            catch (RuntimeException unavailable) { assessed = false; correct = false; }
        }
        final boolean resultAssessed = assessed, resultCorrect = correct;
        return transactions.execute(status -> {
            // Exam start takes the same user lock, closing the AI wait/start race.
            AppUser user = users.findLockedByEmployeeNo(employeeNo).orElseThrow();
            Question q = eligible(questionId, user);
            if (q.getType() != snapshot.type() || !Objects.equals(q.getStem(), snapshot.stem())
                    || !Objects.equals(q.getAnswer(), snapshot.answer()))
                throw new IllegalStateException("题目已更新，请刷新后重新作答");
            PracticeRecord record = new PracticeRecord();
            record.setUser(user); record.setQuestion(q); record.setAnswerText(answer);
            record.setCorrect(resultCorrect); record.setAssessed(resultAssessed); record.setAnsweredAt(Instant.now());
            practices.save(record);
            return new QuestionDtos.PracticeResult(resultAssessed ? resultCorrect : null, q.getAnswer(), q.getExplanation());
        });
    }

    private Question eligible(Long id, AppUser user) {
        if (user.getStatus() != UserStatus.ACTIVE || user.getRoles().stream().noneMatch(r -> r.effectiveAuthority().equals("CANDIDATE")))
            throw new org.springframework.security.access.AccessDeniedException("当前账号不能刷题");
        Question q = questions.findById(id).orElseThrow(() -> new IllegalArgumentException("试题不存在"));
        if (q.getStatus() != QuestionStatus.ACTIVE || q.isDeleted()) throw new IllegalStateException("试题尚未启用");
        boolean protectedQuestion = attempts.findByUserIdAndStatus(user.getId(), AttemptStatus.IN_PROGRESS).stream()
                .anyMatch(a -> a.getPersonalPaper() == null
                    ? a.getExam().getQuestions().stream().anyMatch(item -> item.getQuestion().getId().equals(id))
                    : a.getPersonalPaper().getQuestions().stream().anyMatch(item -> item.getQuestion().getId().equals(id)));
        if (protectedQuestion) throw new IllegalStateException("考试进行中，暂不能查看本次考试题目的刷题反馈");
        return q;
    }
}
