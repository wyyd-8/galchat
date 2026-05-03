package com.me.galchat.interceptor;

import org.json.JSONObject;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DeepSeekThinkingInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException{
        // 仅处理发往 /chat/completions 的请求
        if (request.getURI().getPath().contains("/chat/completions")) {
            String originalBody = new String(body, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(originalBody);
            // 添加 thinking 参数（如果不存在）
            if (!json.has("thinking")) {
                json.put("thinking", new JSONObject().put("type", "disabled"));
            }
            body = json.toString().getBytes(StandardCharsets.UTF_8);
        }
        return execution.execute(request, body);
    }
}