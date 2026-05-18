package com.me.galchat.service.impl;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalDocumentRerankerTest {

    @Test
    void rerankMapsPythonIndexesBackToDocuments() {
        RestClient.Builder restClientBuilder = RestClient.builder()
                .baseUrl("http://localhost:8082");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo("http://localhost:8082/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "query": "主角是谁",
                          "documents": ["主角是安娜。", "天气很好。", "故事的主角是莉娅。"],
                          "top_n": 2
                        }
                        """))
                .andRespond(withSuccess("""
                        {"results":[{"index":2,"score":0.98},{"index":0,"score":0.72}]}
                        """, MediaType.APPLICATION_JSON));

        LocalDocumentReranker reranker = new LocalDocumentReranker(restClientBuilder);

        List<Document> result = reranker.rerank("主角是谁", List.of(
                document("doc-0", "主角是安娜。"),
                document("doc-1", "天气很好。"),
                document("doc-2", "故事的主角是莉娅。")
        ), 2);

        System.out.println(result.toString());
        assertThat(result).extracting(Document::getId).containsExactly("doc-2", "doc-0");
        server.verify();
    }

    @Test
    void rerankFallsBackToOriginalOrderWhenPythonServiceFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalDocumentReranker.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);

        try {
            RestClient.Builder restClientBuilder = RestClient.builder()
                    .baseUrl("http://localhost:8082");
            MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
            server.expect(requestTo("http://localhost:8082/rerank"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withServerError());

            LocalDocumentReranker reranker = new LocalDocumentReranker(restClientBuilder);

            List<Document> result = reranker.rerank("主角是谁", List.of(
                    document("doc-0", "主角是安娜。"),
                    document("doc-1", "天气很好。"),
                    document("doc-2", "故事的主角是莉娅。")
            ), 2);

            assertThat(result).extracting(Document::getId).containsExactly("doc-0", "doc-1");
            server.verify();
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void rerankIgnoresInvalidIndexesFromPythonService() {
        RestClient.Builder restClientBuilder = RestClient.builder()
                .baseUrl("http://localhost:8082");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo("http://localhost:8082/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"results":[{"index":99,"score":1.0},{"index":1,"score":0.9},{"index":1,"score":0.8}]}
                        """, MediaType.APPLICATION_JSON));

        LocalDocumentReranker reranker = new LocalDocumentReranker(restClientBuilder);

        List<Document> result = reranker.rerank("主角是谁", List.of(
                document("doc-0", "主角是安娜。"),
                document("doc-1", "故事的主角是莉娅。")
        ), 3);

        assertThat(result).extracting(Document::getId).containsExactly("doc-1");
        server.verify();
    }

    private static Document document(String id, String text) {
        return Document.builder()
                .id(id)
                .text(text)
                .build();
    }
}
