package com.me.galchat.service.impl;

import com.me.galchat.service.DocumentReranker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
public class LocalDocumentReranker implements DocumentReranker {

    private final RestClient restClient;

    public LocalDocumentReranker() {
        this("http://localhost:8082");
    }

    LocalDocumentReranker(String baseUrl) {
        this(restClientBuilder(baseUrl));
    }

    LocalDocumentReranker(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    private static RestClient.Builder restClientBuilder(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
    }

    @Override
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty() || topN <= 0) {
            return List.of();
        }

        List<Document> safeDocuments = documents.stream()
                .filter(Document::isText)
                .toList();

        if (safeDocuments.isEmpty()) {
            return documents.stream().limit(topN).toList();
        }

        try {
            RerankResponse response = restClient.post()
                    .uri("/rerank")
                    .body(new RerankRequest(
                            query == null ? "" : query,
                            safeDocuments.stream().map(Document::getText).toList(),
                            topN
                    ))
                    .retrieve()
                    .body(RerankResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return safeDocuments.stream().limit(topN).toList();
            }

            return response.results().stream()
                    .map(RerankResult::index)
                    .filter(index -> index >= 0 && index < safeDocuments.size())
                    .distinct()
                    .limit(topN)
                    .map(safeDocuments::get)
                    .toList();
        } catch (Exception e) {
            log.warn("本地reranker调用失败，返回向量召回原始顺序", e);
            return safeDocuments.stream().limit(topN).toList();
        }
    }

    public record RerankRequest(String query, List<String> documents, int top_n) {
    }

    public record RerankResponse(List<RerankResult> results) {
    }

    public record RerankResult(int index, double score) {
    }
}
