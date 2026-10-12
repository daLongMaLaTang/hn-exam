package com.huaneng.shanyin.hnexam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeepSeekConnectionTest {
    @TempDir Path temp;
    @Test void encryptedKeysSurviveRestartAndRejectTamperingOrMissingMaster() throws Exception {
        Path file=temp.resolve("master.key"); var cipher=new AiKeyCipher(file.toString());
        String a=cipher.encrypt("sk-test-secret"), b=cipher.encrypt("sk-test-secret");
        assertThat(a).isNotEqualTo(b).doesNotContain("sk-test-secret");
        assertThat(new AiKeyCipher(file.toString()).decrypt(a)).isEqualTo("sk-test-secret");
        assertThatThrownBy(()->cipher.decrypt(a.substring(0,8)+"AAAA"+a.substring(12))).isInstanceOf(IllegalStateException.class);
        Files.delete(file);
        assertThatThrownBy(()->cipher.decrypt(a)).hasMessageContaining("无法读取");
        assertThat(file).doesNotExist();
    }
    @Test void verifiesOnlyWithOfficialModelsEndpointAndSanitizesFailures() throws Exception {
        var connector=new DeepSeekConnection(new ObjectMapper());var client=mock(HttpClient.class);
        ReflectionTestUtils.setField(connector,"http",client);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"data\":[{\"id\":\"deepseek-flash\"}]}");
        connector.verify("sk-test-secret");
        var request=ArgumentCaptor.forClass(HttpRequest.class);verify(client).send(request.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.deepseek.com/models");
        assertThat(request.getValue().method()).isEqualTo("GET");assertThat(request.getValue().bodyPublisher()).isEmpty();
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer sk-test-secret");
        when(response.statusCode()).thenReturn(401);when(response.body()).thenReturn("secret echo: sk-test-secret");
        assertThatThrownBy(()->connector.verify("sk-test-secret")).hasMessageContaining("无效").hasMessageNotContaining("sk-test-secret");
        when(response.statusCode()).thenReturn(429);
        assertThatThrownBy(()->connector.verify("sk-test-secret")).hasMessageContaining("原配置未更改");
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"data\":[]}");
        assertThatThrownBy(()->connector.verify("sk-test-secret")).hasMessageContaining("未返回可用");
        assertThatThrownBy(()->connector.verify("sk-bad\nheader")).isInstanceOf(IllegalArgumentException.class);
    }
}
