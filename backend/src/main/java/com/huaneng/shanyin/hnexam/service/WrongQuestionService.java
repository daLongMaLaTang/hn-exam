package com.huaneng.shanyin.hnexam.service;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.entity.*;
import com.huaneng.shanyin.hnexam.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Daily and exam incidents are independent, even for the same question. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class WrongQuestionService {
    private final PracticeRecordRepository practices;
    private final ExamAttemptRepository attempts;
    public record Event(String source, Long sourceId, Long questionId, Instant at, String action) {}
    public record Summary(long entered, long resolved, long unresolved, double resolutionRate, List<Event> history) {}
    private record Fact(Question question, String source, Long id, Instant at, boolean wrong, boolean clears) {}
    private record State(List<Question> unresolved, Summary summary) {}
    public List<Question> unresolved(Long userId, String source) { return replay(userId, source).unresolved(); }
    public Summary summary(Long userId) { return replay(userId, null).summary(); }
    public Summary summary(Long userId, String source) { return replay(userId, source).summary(); }
    private State replay(Long userId, String source) {
        List<Fact> facts = new ArrayList<>();
        practices.findByUserIdOrderByAnsweredAtDesc(userId).forEach(r -> {
            if (!Boolean.FALSE.equals(r.getAssessed())) facts.add(new Fact(r.getQuestion(), "DAILY", r.getId(), r.getAnsweredAt(), !r.isCorrect(), r.isCorrect()));
        });
        attempts.findByUserIdOrderByStartedAtDesc(userId).stream().filter(a -> a.getStatus() == AttemptStatus.GRADED && a.getExam().getStatus() != ExamStatus.CANCELLED).forEach(a -> a.getAnswers().forEach(r -> {
            QuestionType type = a.getPersonalPaper() == null ? a.getExam().getQuestions().stream().filter(q -> q.getQuestion().getId().equals(r.getQuestion().getId())).map(q -> q.getSnapshotType() == null ? q.getQuestion().getType() : q.getSnapshotType()).findFirst().orElse(r.getQuestion().getType()) : a.getPersonalPaper().getQuestions().stream().filter(q -> q.getQuestion().getId().equals(r.getQuestion().getId())).map(PersonalExamQuestion::getSnapshotType).findFirst().orElse(r.getQuestion().getType());
            boolean correct;
            if(type==QuestionType.SHORT_ANSWER && ShortAnswerScoring.POLICY.equals(r.getShortScoringPolicyVersion())) {
                if(r.getAwardedPoints()==null || !"ACCEPTED".equals(r.getShortReviewState())) return;
                Integer maximum=a.getPersonalPaper()==null
                    ?a.getExam().getQuestions().stream().filter(q->q.getQuestion().getId().equals(r.getQuestion().getId())).map(ExamQuestion::getPoints).findFirst().orElse(null)
                    :a.getPersonalPaper().getQuestions().stream().filter(q->q.getQuestion().getId().equals(r.getQuestion().getId())).map(PersonalExamQuestion::getPoints).findFirst().orElse(null);
                if(maximum==null || maximum<=0 || r.getAwardedPoints()<0 || r.getAwardedPoints()>maximum) return;
                correct=ShortAnswerScoring.correctByScore(r.getAwardedPoints(),maximum);
            } else {
                // Null policy retains the historical semantic rule and replay order.
                if(type==QuestionType.SHORT_ANSWER && r.getSemanticSimilarity()==null) return;
                correct=type==QuestionType.SHORT_ANSWER?r.getSemanticSimilarity()>=30:Boolean.TRUE.equals(r.getCorrect());
            }
            facts.add(new Fact(r.getQuestion(), "EXAM", a.getId(), a.getSubmittedAt(), !correct, correct && a.getExam().isWrongQuestionPaper()));
        }));
        facts.sort(Comparator.comparing(Fact::at).thenComparing(Fact::source).thenComparing(Fact::id));
        Map<String, Question> open = new LinkedHashMap<>();
        Map<Long, Integer> streaks = new HashMap<>();
        List<Event> history = new ArrayList<>(); long entered=0, resolved=0;
        for (Fact f : facts) {
            Long id = f.question().getId();
            if (source != null && !source.equals(f.source())) continue;
            String key = f.source() + ":" + id;
            boolean clears = f.clears();
            if (f.source().equals("DAILY")) {
                if (f.wrong()) streaks.put(id, 0);
                else if (open.containsKey(key)) clears = streaks.merge(id, 1, Integer::sum) >= 2;
            }
            if (f.wrong() && !open.containsKey(key)) {
                open.put(key, f.question()); entered++; history.add(new Event(f.source(), f.id(), id, f.at(), "ENTER"));
            } else if (clears && open.remove(key) != null) {
                resolved++; history.add(new Event(f.source(), f.id(), id, f.at(), "RESOLVE"));
            }
        }
        Collections.reverse(history);
        return new State(new ArrayList<>(open.values()), new Summary(entered, resolved, open.size(), entered == 0 ? 0 : 100.0*resolved/entered, history));
    }
}
