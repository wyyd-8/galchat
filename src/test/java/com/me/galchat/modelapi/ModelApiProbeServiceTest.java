package com.me.galchat.modelapi;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class ModelApiProbeServiceTest {

    @Test
    void reportsSuccessOnlyWhenChatStreamingAndToolCallingAreSupported() {
        QueueTransport transport = new QueueTransport(
                response(200, """
                        {"choices":[{"message":{"role":"assistant","content":"OK",
                        "reasoning_content":"checked"}}]}
                        """),
                streamResponse(200, List.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}")),
                response(200, """
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"type":"function","function":
                        {"name":"get_test_value","arguments":"{}"}}]}}]}
                        """));
        ModelApiProbeService service = service(transport);

        ModelApiProbeResult result = service.probe(
                URI.create("https://models.example.com/openai/v1"),
                "sk-secret", "model-a");

        assertThat(result.status()).isEqualTo(ModelApiTestStatus.SUCCESS);
        assertThat(result.chat()).isEqualTo(ModelApiCapability.SUPPORTED);
        assertThat(result.streaming()).isEqualTo(ModelApiCapability.SUPPORTED);
        assertThat(result.toolCalling()).isEqualTo(ModelApiCapability.SUPPORTED);
        assertThat(result.reasoningOutput())
                .isEqualTo(ReasoningOutputStatus.DETECTED);
        assertThat(result.code()).isEqualTo("OK");
        assertThat(transport.requests).hasSize(3)
                .allSatisfy(request -> {
                    assertThat(request.uri().toString()).isEqualTo(
                            "https://models.example.com/openai/v1/chat/completions");
                    assertThat(request.apiKey()).isEqualTo("sk-secret");
                    assertThat(request.body()).contains("\"model\":\"model-a\"");
                });
    }

    @Test
    void stopsAfterBasicAuthenticationFailure() {
        QueueTransport transport = new QueueTransport(response(401,
                "{\"error\":{\"message\":\"invalid key\"}}"));

        ModelApiProbeResult result = service(transport).probe(
                URI.create("https://models.example.com/v1"),
                "sk-secret", "model-a");

        assertThat(result.status()).isEqualTo(ModelApiTestStatus.FAILED);
        assertThat(result.chat()).isEqualTo(ModelApiCapability.UNSUPPORTED);
        assertThat(result.streaming()).isEqualTo(ModelApiCapability.UNKNOWN);
        assertThat(result.toolCalling()).isEqualTo(ModelApiCapability.UNKNOWN);
        assertThat(result.code()).isEqualTo("AUTH_FAILED");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void treatsTextOnlyToolResponseAsInconclusiveAndOverallPartial() {
        QueueTransport transport = new QueueTransport(
                response(200, chat("OK")),
                streamResponse(200, List.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}")),
                response(200, chat("I will not call it")));

        ModelApiProbeResult result = service(transport).probe(
                URI.create("https://models.example.com/v1"),
                "sk-secret", "model-a");

        assertThat(result.status()).isEqualTo(ModelApiTestStatus.PARTIAL);
        assertThat(result.toolCalling())
                .isEqualTo(ModelApiCapability.INCONCLUSIVE);
        assertThat(result.reasoningOutput())
                .isEqualTo(ReasoningOutputStatus.NOT_DETECTED);
        assertThat(result.code()).isEqualTo("CAPABILITY_PARTIAL");
    }

    @Test
    void recordsExplicitToolRejectionAsUnsupported() {
        QueueTransport transport = new QueueTransport(
                response(200, chat("OK")),
                streamResponse(200, List.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}")),
                response(400, "{\"error\":{\"message\":\"tools are not supported\"}}"));

        ModelApiProbeResult result = service(transport).probe(
                URI.create("https://models.example.com/v1"),
                "sk-secret", "model-a");

        assertThat(result.status()).isEqualTo(ModelApiTestStatus.PARTIAL);
        assertThat(result.toolCalling())
                .isEqualTo(ModelApiCapability.UNSUPPORTED);
    }

    private ModelApiProbeService service(QueueTransport transport) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ModelApiProbeService(transport,
                new ModelApiProbeResponseInterpreter(objectMapper), objectMapper);
    }

    private static String chat(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"}}]}";
    }

    private static ModelApiHttpTransport.Response response(int status, String body) {
        return new ModelApiHttpTransport.Response(status, body, List.of());
    }

    private static ModelApiHttpTransport.Response streamResponse(
            int status, List<String> lines) {
        return new ModelApiHttpTransport.Response(status, "", lines);
    }

    private static class QueueTransport implements ModelApiHttpTransport {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private QueueTransport(Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response post(Request request) {
            requests.add(request);
            return responses.remove();
        }
    }
}
