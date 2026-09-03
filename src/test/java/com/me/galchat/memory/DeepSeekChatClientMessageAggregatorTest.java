package com.me.galchat.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekChatClientMessageAggregatorTest {

    @Test
    void aggregateChatClientResponseKeepsDeepSeekReasoningContent() {
        ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();
        AtomicReference<ChatClientResponse> aggregatedResponse = new AtomicReference<>();

        List<ChatClientResponse> streamedResponses = aggregator.aggregateChatClientResponse(Flux.just(
                        response(new DeepSeekAssistantMessage.Builder()
                                .content("")
                                .reasoningContent("先")
                                .build()),
                        response(new DeepSeekAssistantMessage.Builder()
                                .content("答案")
                                .reasoningContent("先调用工具")
                                .build())
                ), aggregatedResponse::set)
                .collectList()
                .block();

        assertThat(streamedResponses).hasSize(2);
        assertThat(streamedResponses)
                .extracting(response -> AssistantReasoning.get(response.chatResponse().getResult().getOutput()))
                .containsExactly("先", "先调用工具");
        assertThat(aggregatedResponse.get()).isNotNull();
        assertThat(aggregatedResponse.get().context()).containsEntry("conversation", "1");
        assertThat(aggregatedOutput(aggregatedResponse)).isExactlyInstanceOf(AssistantMessage.class);

        AssistantMessage output = aggregatedOutput(aggregatedResponse);
        assertThat(output.getText()).isEqualTo("答案");
        assertThat(output.getMetadata()).containsEntry("reasoningContent", "先先调用工具");
    }

    @Test
    void aggregateChatClientResponseConvertsGenericCumulativeReasoningToSuffixDeltas() {
        ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();
        AtomicReference<ChatClientResponse> aggregatedResponse = new AtomicReference<>();

        List<ChatClientResponse> streamedResponses = aggregator.aggregateChatClientResponse(Flux.just(
                        response(AssistantMessage.builder()
                                .content("")
                                .properties(Map.of("id", "first-call", "reasoningContent", "先"))
                                .build()),
                        response(AssistantMessage.builder()
                                .content("")
                                .properties(Map.of("id", "first-call", "reasoningContent", "先想"))
                                .build()),
                        response(AssistantMessage.builder()
                                .content("")
                                .properties(Map.of("id", "second-call", "reasoningContent", "再"))
                                .build()),
                        response(AssistantMessage.builder()
                                .content("答案")
                                .properties(Map.of("id", "second-call", "reasoningContent", "再想"))
                                .build())
                ), aggregatedResponse::set)
                .collectList()
                .block();

        assertThat(streamedResponses)
                .extracting(response -> AssistantReasoning.get(response.chatResponse().getResult().getOutput()))
                .containsExactly("先", "想", "再", "想");
        assertThat(aggregatedOutput(aggregatedResponse).getText()).isEqualTo("答案");
        assertThat(aggregatedOutput(aggregatedResponse).getMetadata())
                .containsEntry("reasoningContent", "先想再想");
    }

    private org.springframework.ai.chat.messages.AssistantMessage aggregatedOutput(
            AtomicReference<ChatClientResponse> aggregatedResponse) {
        return aggregatedResponse.get().chatResponse().getResult().getOutput();
    }

    private ChatClientResponse response(AssistantMessage message) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(message))))
                .context(Map.of("conversation", "1"))
                .build();
    }
}
