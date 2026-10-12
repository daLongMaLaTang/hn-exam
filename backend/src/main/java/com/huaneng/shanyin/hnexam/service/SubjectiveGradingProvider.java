package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.QuestionType;

import java.util.Optional;

/**
 * Extension point for a future AI grading provider. Implementations may only return suggestions;
 * a human reviewer remains responsible for persisting the final score.
 */
public interface SubjectiveGradingProvider {
    record Request(Long attemptId, Long questionId, QuestionType type, String stem,
                   String referenceAnswer, String candidateAnswer, int maximumPoints) {}
    record Suggestion(int points, double semanticSimilarity, String reason, String provider, String model) {}

    boolean available();
    Optional<Suggestion> suggest(Request request);
}
