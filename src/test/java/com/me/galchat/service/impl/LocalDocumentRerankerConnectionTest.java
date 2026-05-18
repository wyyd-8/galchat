package com.me.galchat.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDocumentRerankerConnectionTest {

    @Test
    @EnabledIfSystemProperty(named = "galchat.reranker.connection-test", matches = "true")
    void connectsToLocalPythonReranker() {
        RerankResponse response = RestClient.builder()
                .baseUrl("http://127.0.0.1:8082")
                .build()
                .post()
                .uri("/rerank")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "query": "帕斯卡杀了谁",
                          "documents": ["帕斯卡在湖边钓鱼", "亚格在湖边杀了帕斯卡", "你知道吗，帕斯卡在房间里杀了格兰"],
                          "top_n": 2
                        }
                        """)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    throw new AssertionError("Python reranker returned " + clientResponse.getStatusCode());
                })
                .body(RerankResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.results()).hasSize(2);
        assertThat(response.results())
                .allSatisfy(result -> {
                    assertThat(result.index()).isBetween(0, 2);
                    assertThat(result.score()).isFinite();
                });
        System.out.println(response.results());
    }

    private record RerankResponse(java.util.List<RerankResult> results) {
    }

    private record RerankResult(int index, double score) {
    }
}
