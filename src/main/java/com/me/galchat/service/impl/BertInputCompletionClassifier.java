package com.me.galchat.service.impl;

import com.me.galchat.service.InputCompletionClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Service
@Slf4j
public class BertInputCompletionClassifier implements InputCompletionClassifier {

    private final RestClient restClient;

    public BertInputCompletionClassifier() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));

        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:8081")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public boolean isComplete(String text, String context) {
        try {
            Boolean result = restClient.post()
                    .uri("/predict")
                    .body(Map.of(
                            "text", text == null ? "" : text,
                            "context", context == null ? "" : context
                    ))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("BERT完整性判断失败，使用3秒保底任务兜底", e);
            return false;
        }
    }
}
