package com.me.galchat.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
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
        DeepSeekChatClientMessageAggregator aggregator = new DeepSeekChatClientMessageAggregator();
        AtomicReference<ChatClientResponse> aggregatedResponse = new AtomicReference<>();

        List<ChatClientResponse> streamedResponses = aggregator.aggregateChatClientResponse(Flux.just(
                        response(new DeepSeekAssistantMessage.Builder()
                                .content("")
                                .reasoningContent("先想")
                                .build()),
                        response(new DeepSeekAssistantMessage.Builder()
                                .content("答案")
                                .reasoningContent("，再想")
                                .build())
                ), aggregatedResponse::set)
                .collectList()
                .block();

        assertThat(streamedResponses).hasSize(2);
        assertThat(aggregatedResponse.get()).isNotNull();
        assertThat(aggregatedResponse.get().context()).containsEntry("conversation", "1");
        assertThat(aggregatedOutput(aggregatedResponse)).isInstanceOf(DeepSeekAssistantMessage.class);

        DeepSeekAssistantMessage output = (DeepSeekAssistantMessage) aggregatedOutput(aggregatedResponse);
        assertThat(output.getText()).isEqualTo("答案");
        assertThat(output.getReasoningContent()).isEqualTo("先想，再想");
    }

    private org.springframework.ai.chat.messages.AssistantMessage aggregatedOutput(
            AtomicReference<ChatClientResponse> aggregatedResponse) {
        return aggregatedResponse.get().chatResponse().getResult().getOutput();
    }

    private ChatClientResponse response(DeepSeekAssistantMessage message) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(message))))
                .context(Map.of("conversation", "1"))
                .build();
    }
}
