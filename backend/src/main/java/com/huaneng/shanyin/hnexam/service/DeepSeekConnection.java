package com.huaneng.shanyin.hnexam.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Service
public class DeepSeekConnection {
    public static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    public static final String MODEL = "deepseek-flash";
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public DeepSeekConnection(ObjectMapper json) { this.json = json; }
    /** Authenticate without sending exam content or generating a paid completion. */
    public void verify(String key) {
        if (key == null || !key.matches("[\\x21-\\x7E]{10,512}"))
            throw new IllegalArgumentException("请填写有效的DeepSeek API Key，不要填写网址或包含空格");
        HttpResponse<String> response;
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/models"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + key).GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("连接验证已中断，请重试");
        } catch (Exception e) { throw new IllegalStateException("无法连接DeepSeek，请检查服务器网络后重试，原配置未更改"); }
        if (response.statusCode() == 401 || response.statusCode() == 403)
            throw new IllegalArgumentException("DeepSeek API Key无效或没有权限，请检查后重试，原配置未更改");
        if (response.statusCode() != 200)
            throw new IllegalStateException("DeepSeek暂时无法验证，请稍后重试，原配置未更改");
        try {
            if (response.body().length() > 1000000) throw new IllegalArgumentException();
            for (var model : json.readTree(response.body()).path("data"))
                if (MODEL.equals(model.path("id").asText())) return;
        } catch (Exception ignored) { }
        throw new IllegalStateException("DeepSeek未返回可用的判卷模型，请稍后重试，原配置未更改");
    }
}
