package com.me.galchat.modelapi;

import com.me.galchat.domain.po.UserModelApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class UserModelRuntimeProbeIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void probesThroughTheDynamicSpringAiModelWithOverridesOnEveryRequest()
            throws Exception {
        Queue<UpstreamResponse> responses = new ArrayDeque<>(List.of(
                json(chatResponse("basic", "OK")),
                sse(streamResponse()),
                json(toolCallResponse()),
                json(chatResponse("tool-final", "test-value received"))));
        List<CapturedRequest> requests = new ArrayList<>();
        startServer(responses, requests);

        URI baseUrl = URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1");
        ChatModel model = new SpringAiOpenAiChatModelFactory(
                Duration.ofSeconds(5)).create(
                baseUrl, "sk-secret", "model-a", Map.of(
                        "thinking", Map.of("type", "enabled"),
                        "reasoning_effort", "high",
                        "max_completion_tokens", 8192));
        UserModelApi configuration = new UserModelApi()
                .setId(41L)
                .setUserId(7L);

        ModelApiProbeResult result = new ModelApiProbeService().probe(
                new ResolvedUserModelRuntime(
                        7L, 41L, configuration, model));

        assertThat(result.status()).isEqualTo(ModelApiTestStatus.SUCCESS);
        assertThat(requests).hasSize(4);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.path()).isEqualTo("/v1/chat/completions");
            assertThat(request.authorization()).isEqualTo("Bearer sk-secret");
            JsonNode body = objectMapper.readTree(request.body());
            assertThat(body.get("model").asString()).isEqualTo("model-a");
            assertThat(body.get("thinking").get("type").asString())
                    .isEqualTo("enabled");
            assertThat(body.get("reasoning_effort").asString())
                    .isEqualTo("high");
            assertThat(body.get("max_completion_tokens").asInt())
                    .isEqualTo(8192);
            assertThat(body.get("max_tokens")).isNull();
        });
        assertThat(objectMapper.readTree(requests.get(2).body()).get("tools"))
                .isNotNull();
        assertThat(requests.get(3).body()).contains("\"role\":\"tool\"");
    }

    private void startServer(
            Queue<UpstreamResponse> responses,
            List<CapturedRequest> requests) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requests.add(capture(exchange));
                UpstreamResponse response = responses.remove();
                exchange.getResponseHeaders().set(
                        "Content-Type", response.contentType());
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private CapturedRequest capture(HttpExchange exchange) throws IOException {
        return new CapturedRequest(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
    }

    private UpstreamResponse json(String body) {
        return new UpstreamResponse("application/json", body);
    }

    private UpstreamResponse sse(String body) {
        return new UpstreamResponse("text/event-stream", body);
    }

    private String chatResponse(String id, String content) {
        return """
                {"id":"%s","object":"chat.completion","created":1,
                "model":"model-a","choices":[{"index":0,"message":
                {"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """.formatted(id, content);
    }

    private String toolCallResponse() {
        return """
                {"id":"tool-call","object":"chat.completion","created":1,
                "model":"model-a","choices":[{"index":0,"message":
                {"role":"assistant","content":null,"tool_calls":[
                {"id":"call-1","type":"function","function":
                {"name":"get_test_value","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """;
    }

    private String streamResponse() {
        return """
                data: {"id":"stream","object":"chat.completion.chunk","created":1,"model":"model-a","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}

                data: {"id":"stream","object":"chat.completion.chunk","created":1,"model":"model-a","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
    }

    private record UpstreamResponse(String contentType, String body) {
    }

    private record CapturedRequest(
            String path, String authorization, String body) {
    }
}
