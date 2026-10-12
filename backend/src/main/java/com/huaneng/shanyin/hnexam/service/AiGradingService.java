package com.huaneng.shanyin.hnexam.service;
import com.huaneng.shanyin.hnexam.domain.*;
import com.huaneng.shanyin.hnexam.dto.ExamDtos;
import com.huaneng.shanyin.hnexam.repository.ExamAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*; import java.util.concurrent.ConcurrentHashMap;

@Service @RequiredArgsConstructor
public class AiGradingService {
    private final ExamService exams; private final ExamAttemptRepository attempts; private final OpenAiCompatibleGradingProvider provider; private final TransactionTemplate tx;
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    public Map<String,String> suggest(Long id,Long questionId) {
        if(!provider.available()) throw new IllegalStateException("AI未启用或缺少配置，请继续人工判卷");
        String key=id+":"+questionId;
        if(!running.add(key)) throw new IllegalStateException("该题正在生成建议，请稍后刷新");
        try {
            ExamDtos.Review review=exams.review(id,null);
            if(review.grade().status()!=AttemptStatus.SUBMITTED) throw new IllegalStateException("仅待判卷答卷可生成建议");
            var q=review.questions().stream().filter(item->item.id().equals(questionId)&&!item.objective()&&item.type()!=QuestionType.FILL_BLANK).findFirst().orElseThrow(()->new IllegalArgumentException("主观题不存在"));
            tx.executeWithoutResult(s->{ var a=attempts.findLockedById(id).orElseThrow(); if(a.getStatus()!=AttemptStatus.SUBMITTED) throw new IllegalStateException("答卷状态已变更"); a.getAnswers().stream().filter(v->v.getQuestion().getId().equals(questionId)).forEach(v->{v.setAiStatus("RUNNING");v.setAiSuggestedPoints(null);v.setAiSuggestedSimilarity(null);v.setAiSuggestionReason(null);}); });
            try {
                var suggestion=provider.suggest(new SubjectiveGradingProvider.Request(id,questionId,q.type(),q.stem(),q.correctAnswer(),q.savedAnswer(),q.points())).orElseThrow();
                tx.executeWithoutResult(s->{var a=attempts.findLockedById(id).orElseThrow(); a.getAnswers().stream().filter(v->v.getQuestion().getId().equals(questionId)).forEach(v->{
                    if(a.getStatus()!=AttemptStatus.SUBMITTED || a.getExam().getStatus()==ExamStatus.CANCELLED) { v.setAiStatus("DISCARDED"); return; }
                    v.setAiSuggestedPoints(suggestion.points());v.setAiSuggestedSimilarity(suggestion.semanticSimilarity());v.setAiSuggestionReason(suggestion.reason());v.setAiModel(suggestion.model());v.setAiStatus("READY");
                });});
                return Map.of("status","READY");
            } catch(Exception e) {
                tx.executeWithoutResult(s->{var a=attempts.findLockedById(id).orElseThrow(); a.getAnswers().stream().filter(v->v.getQuestion().getId().equals(questionId)).forEach(v->v.setAiStatus("FAILED"));});
                throw new IllegalStateException("AI建议生成失败，该题可重试或人工评分");
            }
        } finally { running.remove(key); }
    }
}
