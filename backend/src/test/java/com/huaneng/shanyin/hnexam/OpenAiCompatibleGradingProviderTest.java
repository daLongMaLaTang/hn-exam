package com.huaneng.shanyin.hnexam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.AiSettings;
import com.huaneng.shanyin.hnexam.repository.AiSettingsRepository;
import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiCompatibleGradingProviderTest {
    @Test void compatibleWireFormatIsMinimalAndSuggestionsStayIndependent() throws Exception {
        var settings=mock(AiSettingsRepository.class);var c=new AiSettings();c.setEnabled(true);c.setEndpoint("https://grading.example.test/v1/chat/completions");c.setModel("configured-model");c.setTimeoutSeconds(17);when(settings.findById(1L)).thenReturn(Optional.of(c));
        var provider=new OpenAiCompatibleGradingProvider(settings,new ObjectMapper(),"isolated-test-key",mock(AiKeyCipher.class));var client=mock(HttpClient.class);ReflectionTestUtils.setField(provider,"http",client);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"{\\\"points\\\":12,\\\"semanticSimilarity\\\":30,\\\"reason\\\":\\\"要点不足，语义达到标准\\\"}\"}}]}");
        when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var suggestion=provider.suggest(new SubjectiveGradingProvider.Request(999L,888L,QuestionType.SHORT_ANSWER,"作业步骤","先验电再接地","接地前应验电",60)).orElseThrow();
        assertThat(suggestion.points()).isEqualTo(12);assertThat(suggestion.semanticSimilarity()).isEqualTo(30);
        var captor=ArgumentCaptor.forClass(HttpRequest.class);verify(client).send(captor.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        var sent=captor.getValue();assertThat(sent.timeout().orElseThrow().getSeconds()).isEqualTo(17);assertThat(sent.uri().toString()).isEqualTo(c.getEndpoint());
        var content=new StringBuilder();sent.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){content.append(StandardCharsets.UTF_8.decode(b));}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}});
        assertThat(content.toString()).contains("configured-model","json_object","candidateAnswer").doesNotContain("999","888","employeeNo","userId","isolated-test-key");
        when(response.statusCode()).thenReturn(503);
        assertThatThrownBy(()->provider.suggest(new SubjectiveGradingProvider.Request(1L,2L,QuestionType.SHORT_ANSWER,"题","答案","作答",60))).hasMessageContaining("AI请求失败");
    }
    @Test void rejectsMalformedOutOfRangeAndUnsafeConfiguration() {
        var provider=new OpenAiCompatibleGradingProvider(mock(AiSettingsRepository.class),new ObjectMapper(),"",mock(AiKeyCipher.class));
        for(String value:List.of("not json","{}","{\"points\":-1,\"semanticSimilarity\":30,\"reason\":\"bad\"}","{\"points\":1,\"semanticSimilarity\":101,\"reason\":\"bad\"}","{\"points\":1,\"semanticSimilarity\":30,\"reason\":\"\"}")) assertThatThrownBy(()->provider.parseSuggestion(value,60,"model")).isInstanceOf(Exception.class);
        assertThatThrownBy(()->OpenAiCompatibleGradingProvider.validateEndpoint("http://example.test/v1/chat/completions")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OpenAiCompatibleGradingProvider.validateEndpoint("https://user:secret@example.test/v1/chat/completions")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void savedKeyOverridesEnvironmentImmediatelyAndCannotBeSentToLegacyEndpoint() throws Exception {
        var settings=mock(AiSettingsRepository.class);var c=new AiSettings();
        c.setEnabled(true);c.setEndpoint("https://old.example.test/chat/completions");c.setModel("legacy-model");c.setApiKeyCiphertext("encrypted-first");
        when(settings.findById(1L)).thenReturn(Optional.of(c));
        var cipher=mock(AiKeyCipher.class);when(cipher.decrypt("encrypted-first")).thenReturn("sk-first");when(cipher.decrypt("encrypted-second")).thenReturn("sk-second");
        var provider=new OpenAiCompatibleGradingProvider(settings,new ObjectMapper(),"environment-key",cipher);
        var client=mock(HttpClient.class);ReflectionTestUtils.setField(provider,"http",client);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"{\\\"points\\\":1,\\\"semanticSimilarity\\\":100,\\\"reason\\\":\\\"含义正确\\\"}\"}}]}");
        when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var input=new SubjectiveGradingProvider.Request(1L,2L,QuestionType.FILL_BLANK,"题干","答案","作答",1);
        assertThat(provider.suggest(input).orElseThrow().model()).isEqualTo(DeepSeekConnection.MODEL);
        c.setApiKeyCiphertext("encrypted-second");provider.suggest(input);
        var captor=ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).send(captor.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(captor.getAllValues()).allSatisfy(r->assertThat(r.uri().toString()).isEqualTo(DeepSeekConnection.ENDPOINT));
        assertThat(captor.getAllValues().get(0).headers().firstValue("Authorization")).contains("Bearer sk-first");
        assertThat(captor.getAllValues().get(1).headers().firstValue("Authorization")).contains("Bearer sk-second");
        c.setEnabled(false);assertThat(provider.suggest(input)).isEmpty();verifyNoMoreInteractions(client);
    }
}
