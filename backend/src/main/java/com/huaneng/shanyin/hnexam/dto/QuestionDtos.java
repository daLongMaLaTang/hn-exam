package com.huaneng.shanyin.hnexam.dto;

import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class QuestionDtos {
    private QuestionDtos() {}

    public record SaveRequest(
            @NotNull QuestionType type,
            @NotBlank String stem,
            @NotBlank String answer,
            String optionsJson,
            String explanation,
            QuestionStatus status,
            @NotBlank String courseCode,
            String chapter,
            String source,
            @Min(1) @Max(5) int difficulty
    ) {}

    public record AdminView(Long id, QuestionType type, String stem, String answer, String optionsJson,
                            String explanation, QuestionStatus status, String courseCode, String chapter, String source,
                            int difficulty) {
        public static AdminView from(Question q) {
            return new AdminView(q.getId(), q.getType(), q.getStem(), q.getAnswer(), q.getOptionsJson(),
                    q.getExplanation(), q.getStatus(), q.getCourseCode(), normalizedChapter(q), q.getSource(), q.getDifficulty());
        }
    }

    public record CandidateView(Long id, QuestionType type, String stem, String optionsJson,
                                String courseCode, String chapter, int difficulty, int blankCount) {
        public static CandidateView from(Question q) {
            return new CandidateView(q.getId(), q.getType(), q.getStem(), q.getOptionsJson(),
                    q.getCourseCode(), normalizedChapter(q), q.getDifficulty(),
                    q.getType() == QuestionType.FILL_BLANK ? com.huaneng.shanyin.hnexam.service.FillBlankGrading.blankCount(q.getAnswer()) : 0);
        }
    }

    private static String normalizedChapter(Question q) { return q.getChapter() == null || q.getChapter().isBlank() ? "未分章" : q.getChapter(); }

    public record PracticeSubmit(@NotBlank @jakarta.validation.constraints.Size(max=10000) String answer) {}
    public record PracticeResult(Boolean correct, String correctAnswer, String explanation) {}
}
