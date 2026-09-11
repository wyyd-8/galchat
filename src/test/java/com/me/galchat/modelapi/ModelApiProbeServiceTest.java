package com.me.galchat.modelapi;

import com.me.galchat.domain.po.UserModelApi;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelApiProbeServiceTest {

    @Test
    void probesChatStreamingAndTheCompleteSpringAiToolLoop() {
        ScriptedChatModel model = new ScriptedChatModel(true, true);

        ModelApiProbeResult result = new ModelApiProbeService().probe(
                runtime(model));

        assertThat(result.chat()).isEqualTo(ModelApiCapability.SUPPORTED);
        assertThat(result.streaming()).isEqualTo(ModelApiCapability.SUPPORTED);
        assertThat(model.blockingCalls).isEqualTo(3);
        assertThat(result.toolCalling()).isEqualTo(ModelApiCapability.SUPPORTED);
        assertThat(result.status()).isEqualTo(ModelApiTestStatus.SUCCESS);
        assertThat(result.reasoningOutput())
                .isEqualTo(ReasoningOutputStatus.DETECTED);
        assertThat(model.streamingCalls).isEqualTo(1);
    }

    @Test
    void reportsToolCallingAsInconclusiveWhenTheModelOnlyAnswersWithText() {
        ScriptedChatModel model = new ScriptedChatModel(false, false);

        ModelApiProbeResult result = new ModelApiProbeService().probe(
                runtime(model));

        assertThat(result.status()).isEqualTo(ModelApiTestStatus.PARTIAL);
        assertThat(result.toolCalling())
                .isEqualTo(ModelApiCapability.INCONCLUSIVE);
        assertThat(result.reasoningOutput())
                .isEqualTo(ReasoningOutputStatus.NOT_DETECTED);
    }

    @Test
    void mapsARealCallAuthenticationFailureAndStopsFurtherProbes() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new NonTransientAiException("HTTP 401: invalid api key");
            }
        };

        ModelApiProbeResult result = new ModelApiProbeService().probe(
                runtime(model));

        assertThat(result.status()).isEqualTo(ModelApiTestStatus.FAILED);
        assertThat(result.code()).isEqualTo("AUTH_FAILED");
        assertThat(result.streaming()).isEqualTo(ModelApiCapability.UNKNOWN);
        assertThat(result.toolCalling()).isEqualTo(ModelApiCapability.UNKNOWN);
    }

    private ResolvedUserModelRuntime runtime(ChatModel model) {
        UserModelApi configuration = new UserModelApi()
                .setId(41L)
                .setUserId(7L);
        return new ResolvedUserModelRuntime(7L, 41L, configuration, model);
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(content))));
    }

    private static ChatResponse reasoningResponse(String content) {
        AssistantMessage message = AssistantMessage.builder()
                .content(content)
                .properties(Map.of("reasoningContent", "checked"))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse toolCallResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "get_test_value", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder()
                        .finishReason("TOOL_CALLS")
                        .build())));
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final boolean requestTool;
        private final boolean exposeReasoning;
        private int blockingCalls;
        private int streamingCalls;

        private ScriptedChatModel(
                boolean requestTool, boolean exposeReasoning) {
            this.requestTool = requestTool;
            this.exposeReasoning = exposeReasoning;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            blockingCalls++;
            if (blockingCalls == 1) {
                return exposeReasoning
                        ? reasoningResponse("OK") : response("OK");
            }
            if (blockingCalls == 2 && requestTool) {
                return toolCallResponse();
            }
            return response("tool result received");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamingCalls++;
            return Flux.just(response("O"), response("K"));
        }

        @Override
        public OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("model-a").build();
        }
    }
}
