package com.me.galchat.modelapi;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelApiProbeResponseInterpreterTest {

    private final ModelApiProbeResponseInterpreter interpreter =
            new ModelApiProbeResponseInterpreter(new ObjectMapper());

    @Test
    void acceptsAChatCompletionAndDetectsReasoningOutput() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"OK",
                "reasoning_content":"checked"}}]}
                """;

        ModelApiProbeResponseInterpreter.ChatObservation observation =
                interpreter.inspectChat(body);

        assertThat(observation.valid()).isTrue();
        assertThat(observation.reasoningDetected()).isTrue();
    }

    @Test
    void rejectsAJsonResponseWithoutAnAssistantMessage() {
        ModelApiProbeResponseInterpreter.ChatObservation observation =
                interpreter.inspectChat("{\"choices\":[]}");

        assertThat(observation.valid()).isFalse();
        assertThat(observation.reasoningDetected()).isFalse();
    }

    @Test
    void acceptsStreamingChunksWithoutRequiringDoneSentinel() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"O\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"K\"},\"finish_reason\":\"stop\"}]}");

        ModelApiProbeResponseInterpreter.StreamObservation observation =
                interpreter.inspectStream(lines);

        assertThat(observation.validChunkCount()).isEqualTo(3);
        assertThat(observation.completed()).isTrue();
    }

    @Test
    void distinguishesToolCallFromTextOnlySuccess() {
        String toolCall = """
                {"choices":[{"message":{"role":"assistant","content":null,
                "tool_calls":[{"id":"call_1","type":"function","function":
                {"name":"get_test_value","arguments":"{}"}}]}}]}
                """;
        String textOnly = """
                {"choices":[{"message":{"role":"assistant","content":"OK"}}]}
                """;

        assertThat(interpreter.inspectToolCall(toolCall))
                .isEqualTo(ModelApiCapability.SUPPORTED);
        assertThat(interpreter.inspectToolCall(textOnly))
                .isEqualTo(ModelApiCapability.INCONCLUSIVE);
    }
}
