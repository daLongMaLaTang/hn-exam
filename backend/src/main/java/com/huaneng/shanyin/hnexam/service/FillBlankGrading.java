package com.huaneng.shanyin.hnexam.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Ordered blanks; an empty submitted slot must never move later answers forward. */
public final class FillBlankGrading {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private FillBlankGrading() {}

    public record Result(int blanks, int matched, int points, boolean correct, boolean extraAnswers) {
        public String comment() {
            return "自动判分：共 " + blanks + " 空，答对 " + matched + " 空；按答对比例四舍五入得 " + points + " 分。"
                    + (extraAnswers ? "作答数量超过空数，本题计 0 分；请按题目顺序逐空作答。" : "");
        }
    }

    public static Result grade(String expected, String submitted, int totalPoints) {
        List<String> reference = parts(expected);
        if (reference.isEmpty() || reference.stream().anyMatch(String::isEmpty)) {
            return new Result(reference.size(), 0, 0, false, false);
        }
        List<String> answers = submittedParts(submitted);
        boolean extra = answers.size() > reference.size()
                && answers.subList(reference.size(), answers.size()).stream().anyMatch(s -> !s.isEmpty());
        int matched = 0;
        for (int i = 0; i < reference.size() && i < answers.size(); i++) {
            if (matches(reference.get(i), answers.get(i))) matched++;
        }
        int points = extra ? 0 : (int) Math.round((double) Math.max(0, totalPoints) * matched / reference.size());
        return new Result(reference.size(), matched, points, !extra && matched == reference.size(), extra);
    }

    public static void validateReference(String answer) {
        List<String> reference = parts(answer);
        if (reference.isEmpty() || reference.stream().anyMatch(s -> Arrays.stream(s.split("~", -1)).anyMatch(String::isBlank))) {
            throw new IllegalArgumentException("填空题每个空都必须填写参考答案，请按顺序用分号分隔，不能留空");
        }
    }

    public static int blankCount(String reference) {
        return parts(reference).size();
    }

    public static boolean matches(String reference, String answer) {
        return !answer.isBlank() && Arrays.stream(reference.split("~", -1)).map(FillBlankGrading::normalize).anyMatch(answer::equals);
    }

    public static boolean needsSemanticReview(String reference, String submitted) {
        var expected = parts(reference); var answers = submittedParts(submitted);
        if (grade(reference, submitted, 0).extraAnswers()) return false;
        for (int i = 0; i < Math.min(expected.size(), answers.size()); i++)
            if (!answers.get(i).isBlank() && !matches(expected.get(i), answers.get(i))) return true;
        return false;
    }

    public static List<String> submittedParts(String value) {
        // Separate input boxes submit a JSON array inside the existing saved-answer string.
        // Parse only strings, and never split punctuation inside an individual input box.
        if (value != null && value.stripLeading().startsWith("[")) {
            try {
                var array = JSON.readTree(value);
                if (array.isArray()) {
                    List<String> result = new java.util.ArrayList<>();
                    for (var item : array) {
                        if (!item.isTextual()) return List.of();
                        result.add(normalize(item.textValue()));
                    }
                    return result;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Preserve pre-existing free-text answers that happen to begin with '['.
            }
        }
        return parts(value);
    }

    public static List<String> parts(String value) {
        if (value == null || value.isBlank()) return List.of();
        value = value.replaceFirst("[\\r\\n]+$", "");
        // Explicit pipes/newlines allow commas inside a single blank. Legacy question banks use punctuation.
        String separator = value.matches("(?s).*[|｜\\r\\n].*") ? "[|｜]|\\R" : "[,，、;；]";
        return Arrays.stream(value.split(separator, -1)).map(FillBlankGrading::normalize).toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT)
                .replaceFirst("[。.]+$", "").strip();
    }
}
