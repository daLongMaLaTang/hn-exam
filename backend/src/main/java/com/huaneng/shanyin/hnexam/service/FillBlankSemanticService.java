package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.QuestionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Network calls take place outside answer/attempt database locks. */
@Service @RequiredArgsConstructor
public class FillBlankSemanticService {
    private final OpenAiCompatibleGradingProvider provider;
    public FillBlankGrading.Result grade(Long attemptId, Long questionId, String stem, String reference, String submitted, int points) {
        var exact = FillBlankGrading.grade(reference, submitted, points);
        if (!FillBlankGrading.needsSemanticReview(reference, submitted)) return exact;
        var expected = FillBlankGrading.parts(reference); var answers = FillBlankGrading.submittedParts(submitted);
        int matched = exact.matched();
        for (int i = 0; i < Math.min(expected.size(), answers.size()); i++) {
            if (answers.get(i).isBlank() || FillBlankGrading.matches(expected.get(i), answers.get(i))) continue;
            var suggestion = provider.suggest(new SubjectiveGradingProvider.Request(attemptId, questionId, QuestionType.FILL_BLANK,
                    stem + "\n本次判断第 " + (i + 1) + " 空", expected.get(i), answers.get(i), 1))
                    .orElseThrow(() -> new IllegalStateException("AI未配置，需人工复核"));
            if (suggestion.points() == 1) matched++;
        }
        return new FillBlankGrading.Result(expected.size(), matched, (int)Math.round((double)points * matched / expected.size()), matched == expected.size(), false);
    }
}
