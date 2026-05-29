package com.me.galchat.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
class DeepSeekChatClientMessageAggregator {

    Flux<ChatClientResponse> aggregateChatClientResponse(Flux<ChatClientResponse> flux,
                                                         Consumer<ChatClientResponse> onComplete) {
        AggregationState state = new AggregationState();
        return flux
                .doOnSubscribe(subscription -> state.reset())
                .doOnNext(state::append)
                .doOnComplete(() -> emitAggregatedResponse(state, onComplete))
                .doOnError(throwable -> log.error("DeepSeek chat response aggregation error", throwable));
    }

    private void emitAggregatedResponse(AggregationState state, Consumer<ChatClientResponse> onComplete) {
        ChatClientResponse chatClientResponse = state.toChatClientResponse();
        if (chatClientResponse != null) {
            onComplete.accept(chatClientResponse);
        }
        state.reset();
    }

    private static class AggregationState {

        private final AtomicReference<StringBuilder> content = new AtomicReference<>(new StringBuilder());
        private final AtomicReference<StringBuilder> reasoningContent = new AtomicReference<>(new StringBuilder());
        private final AtomicReference<Map<String, Object>> properties = new AtomicReference<>(new HashMap<>());
        private final AtomicReference<List<AssistantMessage.ToolCall>> toolCalls =
                new AtomicReference<>(new ArrayList<>());
        private final AtomicReference<Map<String, Object>> context = new AtomicReference<>(new HashMap<>());
        private final AtomicReference<ChatResponseMetadata> responseMetadata = new AtomicReference<>();
        private final AtomicReference<ChatGenerationMetadata> generationMetadata =
                new AtomicReference<>(ChatGenerationMetadata.NULL);
        private boolean hasAssistantOutput;

        void reset() {
            content.set(new StringBuilder());
            reasoningContent.set(new StringBuilder());
            properties.set(new HashMap<>());
            toolCalls.set(new ArrayList<>());
            context.set(new HashMap<>());
            responseMetadata.set(null);
            generationMetadata.set(ChatGenerationMetadata.NULL);
            hasAssistantOutput = false;
        }

        void append(ChatClientResponse chatClientResponse) {
            if (chatClientResponse.context() != null) {
                context.get().putAll(chatClientResponse.context());
            }
            ChatResponse chatResponse = chatClientResponse.chatResponse();
            if (chatResponse == null) {
                return;
            }
            if (chatResponse.getMetadata() != null) {
                responseMetadata.set(chatResponse.getMetadata());
            }
            for (Generation generation : chatResponse.getResults()) {
                append(generation);
            }
        }

        private void append(Generation generation) {
            if (generation == null || generation.getOutput() == null) {
                return;
            }
            if (generation.getMetadata() != null && generation.getMetadata() != ChatGenerationMetadata.NULL) {
                generationMetadata.set(generation.getMetadata());
            }

            AssistantMessage output = generation.getOutput();
            appendText(content.get(), output.getText());
            if (output instanceof DeepSeekAssistantMessage deepSeekAssistantMessage) {
                appendText(reasoningContent.get(), deepSeekAssistantMessage.getReasoningContent());
            }
            if (output.getMetadata() != null) {
                properties.get().putAll(output.getMetadata());
            }
            if (!CollectionUtils.isEmpty(output.getToolCalls())) {
                toolCalls.get().addAll(output.getToolCalls());
            }
            hasAssistantOutput = true;
        }

        ChatClientResponse toChatClientResponse() {
            if (!hasAssistantOutput) {
                return null;
            }

            AssistantMessage assistantMessage = assistantMessage();
            ChatResponse chatResponse = new ChatResponse(
                    List.of(new Generation(assistantMessage, generationMetadata.get())),
                    responseMetadata.get() == null ? ChatResponseMetadata.builder().build() : responseMetadata.get());
            return ChatClientResponse.builder()
                    .chatResponse(chatResponse)
                    .context(context.get())
                    .build();
        }

        private AssistantMessage assistantMessage() {
            String text = content.get().toString();
            String reasoning = reasoningContent.get().toString();
            if (StringUtils.hasText(reasoning)) {
                return new DeepSeekAssistantMessage.Builder()
                        .content(text)
                        .reasoningContent(reasoning)
                        .properties(properties.get())
                        .toolCalls(toolCalls.get())
                        .build();
            }
            return AssistantMessage.builder()
                    .content(text)
                    .properties(properties.get())
                    .toolCalls(toolCalls.get())
                    .build();
        }

        private void appendText(StringBuilder builder, String text) {
            if (text != null) {
                builder.append(text);
            }
        }
    }
}
