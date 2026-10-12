package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.service.AnswerGrader;
import com.huaneng.shanyin.hnexam.service.FillBlankGrading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class FillBlankGradingTest {
    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("人身；电网；设备。", "人身;电网;设备", 10, 3, 10, true),
                Arguments.of("人身；电网；设备。", "人身；；设备", 10, 2, 7, false),
                Arguments.of("甲；乙；丙", "甲", 10, 1, 3, false),
                Arguments.of("甲；乙；丙", "乙；甲；丙", 10, 1, 3, false),
                Arguments.of("甲；乙；丙", "；乙；丙", 10, 2, 7, false),
                Arguments.of("甲；乙；丙", "甲\n\n丙", 10, 2, 7, false),
                Arguments.of("甲；乙；丙", "\n乙\n丙", 10, 2, 7, false),
                Arguments.of("甲；乙；丙", "甲；乙；丙\n", 10, 3, 10, true),
                Arguments.of("ＡＢＣ、乙、丙。", " abc ,乙,丙 ", 6, 3, 6, true),
                Arguments.of("甲,乙|丙", "甲,乙\n丙", 10, 2, 10, true),
                Arguments.of("甲；乙", "甲；乙；额外", 10, 2, 0, false),
                Arguments.of("甲；乙", "甲；乙；", 10, 2, 10, true),
                Arguments.of("甲；乙", null, 10, 0, 0, false),
                Arguments.of("甲；乙", "；", 10, 0, 0, false),
                Arguments.of("甲", "错误", 10, 0, 0, false),
                // Rounded full points do not make a partly incorrect answer fully correct.
                Arguments.of("甲；乙", "甲；", 1, 1, 1, false));
    }

    @ParameterizedTest @MethodSource("cases")
    void gradesEachPosition(String reference, String submitted, int total, int matched, int points, boolean correct) {
        var result = FillBlankGrading.grade(reference, submitted, total);
        assertThat(result.matched()).isEqualTo(matched);
        assertThat(result.points()).isEqualTo(points);
        assertThat(result.correct()).isEqualTo(correct);
    }

    @Test void invalidReferenceCannotBePublishedOrAccidentallyMarkedCorrect() {
        for (String reference : new String[]{"", "甲；；丙", "；甲", "甲；"}) {
            assertThatThrownBy(() -> FillBlankGrading.validateReference(reference)).isInstanceOf(IllegalArgumentException.class);
            assertThat(FillBlankGrading.grade(reference, reference, 10).correct()).isFalse();
        }
        assertThatCode(() -> FillBlankGrading.validateReference("甲；乙。\n")).doesNotThrowAnyException();
    }

    @Test void practiceRequiresAllBlanksAndChoiceGradingIsUnchanged() {
        var grader = new AnswerGrader();
        assertThat(grader.isObjective(QuestionType.FILL_BLANK)).isTrue();
        assertThat(grader.isCorrect(QuestionType.FILL_BLANK, "甲；乙", "甲；错误")).isFalse();
        assertThat(grader.isCorrect(QuestionType.FILL_BLANK, "甲；乙。", "甲；乙")).isTrue();
        assertThat(grader.isCorrect(QuestionType.MULTIPLE_CHOICE, "A,B", "B,A")).isTrue();
        assertThat(grader.isCorrect(QuestionType.TRUE_FALSE, "正确", "√")).isTrue();
        assertThat(grader.isObjective(QuestionType.SHORT_ANSWER)).isFalse();
    }

    @Test void separateInputPayloadPreservesPositionsAndPunctuation() {
        assertThat(FillBlankGrading.blankCount("人身；电网；设备。")).isEqualTo(3);
        var partial = FillBlankGrading.grade("人身；电网；设备。", "[\"人身\",\"\",\"设备\"]", 10);
        assertThat(partial.points()).isEqualTo(7);
        assertThat(partial.matched()).isEqualTo(2);
        assertThat(FillBlankGrading.grade("甲,乙|丙", "[\"甲,乙\",\"丙\"]", 10).correct()).isTrue();
        assertThat(FillBlankGrading.grade("甲；乙", "[\"甲；乙\",\"\"]", 10).points()).isZero();
        assertThat(FillBlankGrading.grade("甲；乙", "[\"甲\",null]", 10).correct()).isFalse();
    }
}
