package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FillBlankSemanticServiceTest {
    @Test void alternativesAndMissingBlanksDoNotNeedAi() {
        var provider = mock(OpenAiCompatibleGradingProvider.class);
        var grader = new FillBlankSemanticService(provider);
        assertThat(grader.grade(1L, 2L, "题干", "断开~切断；接地", "[\"切断\",\"\"]", 10, 3L).points()).isEqualTo(5);
        verifyNoInteractions(provider);
    }
    @Test void semanticEquivalentGetsCreditButExplicitWrongDoesNot() {
        var provider = mock(OpenAiCompatibleGradingProvider.class);
        when(provider.suggest(any())).thenReturn(Optional.of(new SubjectiveGradingProvider.Suggestion(1, 95, "含义相同", "test", "fixture")),
                Optional.of(new SubjectiveGradingProvider.Suggestion(0, 5, "否定含义相反", "test", "fixture")));
        var grader = new FillBlankSemanticService(provider);
        var result = grader.grade(1L, 2L, "题干", "停电；禁止合闸", "[\"切断电源\",\"允许合闸\"]", 9, 3L);
        assertThat(result.points()).isEqualTo(5); assertThat(result.correct()).isFalse();
        verify(provider, times(2)).suggest(any());
    }
    @Test void unavailableAiDoesNotSilentlyMarkSemanticAnswerWrong() {
        var provider = mock(OpenAiCompatibleGradingProvider.class);
        when(provider.suggest(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new FillBlankSemanticService(provider).grade(1L, 2L, "题干", "停电", "切断电源", 10, 3L)).hasMessageContaining("人工复核");
    }
    @Test void alternativesCannotContainEmptyValues() {
        assertThatThrownBy(() -> FillBlankGrading.validateReference("断开~；接地")).isInstanceOf(IllegalArgumentException.class);
    }
}
