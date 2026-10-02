package com.me.galchat.service.impl.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalDocumentRerankerTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://reranker");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final LocalDocumentReranker reranker = new LocalDocumentReranker(builder);

    @Test
    void failedRerankSelectsFiveHighestVectorScoresAcrossSources() {
        server.expect(requestTo("http://reranker/rerank")).andRespond(withServerError());
        List<Document> documents = List.of(
                document("world-low", 0.51), document("world-mid", 0.7),
                document("world-high", 0.8), document("world-low-2", 0.52),
                document("world-low-3", 0.53), document("single-history", 0.98),
                document("group-history", 0.95), document("other-history", 0.9),
                document("recent-unscored", null));

        assertThat(reranker.rerank("query", documents, 5)).extracting(Document::getText)
                .containsExactly("single-history", "group-history", "other-history", "world-high", "world-mid");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"results\":[]}",
            "{\"results\":[{\"index\":99,\"score\":1}]}",
            "{\"results\":[{\"index\":0,\"score\":1},{\"index\":0,\"score\":0}]}",
            "{\"results\":[{\"index\":0,\"score\":1}]}"})
    void unusableResponseFallsBackToVectorScores(String body) {
        server.expect(requestTo("http://reranker/rerank"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(reranker.rerank("query", List.of(document("low", 0.5), document("high", 0.9)), 2))
                .extracting(Document::getText).containsExactly("high", "low");
    }

    @Test
    void fallbackExcludesMissingAndNonFiniteScoresAndPreservesTies() {
        server.expect(requestTo("http://reranker/rerank")).andRespond(withServerError());
        assertThat(reranker.rerank("query", List.of(document("missing", null),
                document("nan", Double.NaN), document("infinite", Double.POSITIVE_INFINITY),
                document("first", 0.8), document("second", 0.8), document("negative", -0.1)), 5))
                .extracting(Document::getText).containsExactly("first", "second", "negative");
    }

    @Test
    void successfulRerankKeepsModelOrderInsteadOfVectorOrder() {
        server.expect(requestTo("http://reranker/rerank"))
                .andRespond(withSuccess("{\"results\":[{\"index\":1,\"score\":2},{\"index\":0,\"score\":1}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(reranker.rerank("query", List.of(document("high-vector", 0.9),
                document("relevant", null)), 2))
                .extracting(Document::getText).containsExactly("relevant", "high-vector");
    }

    private Document document(String text, Double score) {
        return Document.builder().text(text).score(score).build();
    }
}
