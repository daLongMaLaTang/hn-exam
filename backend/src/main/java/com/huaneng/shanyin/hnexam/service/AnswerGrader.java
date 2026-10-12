package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AnswerGrader {
    public boolean isObjective(Question question) {
        return isObjective(question.getType());
    }

    public boolean isObjective(QuestionType type) {
        return type != null && type.isAutomaticallyGraded();
    }

    public boolean isCorrect(Question question, String submitted) {
        return isCorrect(question.getType(), question.getAnswer(), submitted);
    }

    public boolean isCorrect(QuestionType type, String expected, String submitted) {
        if (!isObjective(type) || submitted == null || submitted.isBlank() || expected == null || expected.isBlank()) {
            return false;
        }
        if (type == QuestionType.MULTIPLE_CHOICE) {
            return normalizeSet(submitted).equals(normalizeSet(expected));
        }
        if (type == QuestionType.FILL_BLANK) return FillBlankGrading.grade(expected, submitted, 0).correct();
        if (type == QuestionType.TRUE_FALSE) return normalizeBoolean(submitted).equals(normalizeBoolean(expected));
        return normalize(submitted).equals(normalize(expected));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeBoolean(String value) {
        String v = normalize(value);
        if (Set.of("正确", "对", "是", "true", "√", "t", "1").contains(v)) return "true";
        if (Set.of("错误", "错", "否", "false", "×", "f", "0").contains(v)) return "false";
        return v;
    }

    private Set<String> normalizeSet(String value) {
        return Arrays.stream(value.split("[,，、;；]+"))
                .map(this::normalize)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }
}
