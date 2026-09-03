package com.me.galchat.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
class ChatClientMessageAggregator {

    Flux<ChatClientResponse> aggregateChatClientResponse(
            Flux<ChatClientResponse> flux,
            Consumer<ChatClientResponse> onComplete) {
        AggregationState state = new AggregationState();
        return flux
                .doOnSubscribe(subscription -> state.reset())
                .map(state::normalizeAndAppend)
                .doOnComplete(() -> emitAggregatedResponse(state, onComplete))
                .doOnError(throwable -> log.error("Chat response aggregation error", throwable));
    }

    private void emitAggregatedResponse(
            AggregationState state,
            Consumer<ChatClientResponse> onComplete) {
        ChatClientResponse response = state.toChatClientResponse();
        if (response != null) {
            onComplete.accept(response);
        }
        state.reset();
    }

    private static class AggregationState {

        private final AtomicReference<StringBuilder> content =
                new AtomicReference<>(new StringBuilder());
        private final AtomicReference<StringBuilder> reasoningContent =
                new AtomicReference<>(new StringBuilder());
        private final AtomicReference<Map<String, Object>> properties =
                new AtomicReference<>(new HashMap<>());
        private final AtomicReference<List<AssistantMessage.ToolCall>> toolCalls =
                new AtomicReference<>(new ArrayList<>());
        private final AtomicReference<Map<String, Object>> context =
                new AtomicReference<>(new HashMap<>());
        private final Map<String, String> cumulativeReasoningByStream = new HashMap<>();
        private final AtomicReference<ChatResponseMetadata> responseMetadata =
                new AtomicReference<>();
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
            cumulativeReasoningByStream.clear();
            hasAssistantOutput = false;
        }

        ChatClientResponse normalizeAndAppend(ChatClientResponse response) {
            ChatClientResponse normalized = normalizeReasoning(response);
            append(normalized);
            return normalized;
        }

        private ChatClientResponse normalizeReasoning(ChatClientResponse response) {
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse == null || CollectionUtils.isEmpty(chatResponse.getResults())) {
                return response;
            }

            List<Generation> generations = new ArrayList<>(chatResponse.getResults().size());
            for (int index = 0; index < chatResponse.getResults().size(); index++) {
                Generation generation = chatResponse.getResults().get(index);
                AssistantMessage output = generation.getOutput();
                if (!AssistantReasoning.isCumulativeSnapshot(output)) {
                    generations.add(generation);
                    continue;
                }

                String snapshot = AssistantReasoning.get(output);
                String delta = reasoningDelta(streamKey(chatResponse, output, index), snapshot);
                Map<String, Object> properties = new HashMap<>(output.getMetadata());
                properties.remove(AssistantReasoning.METADATA_KEY);
                if (delta != null && !delta.isEmpty()) {
                    properties.put(AssistantReasoning.METADATA_KEY, delta);
                }
                AssistantMessage normalizedOutput = AssistantMessage.builder()
                        .content(output.getText())
                        .properties(properties)
                        .toolCalls(output.getToolCalls())
                        .media(output.getMedia())
                        .build();
                generations.add(new Generation(normalizedOutput, generation.getMetadata()));
            }
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(generations, chatResponse.getMetadata()))
                    .context(response.context())
                    .build();
        }

        private String streamKey(ChatResponse response, AssistantMessage output, int generationIndex) {
            Object outputId = output.getMetadata().get("id");
            String responseId = response.getMetadata() == null ? null : response.getMetadata().getId();
            Object streamId = outputId == null ? responseId : outputId;
            return (streamId == null ? "default" : streamId.toString()) + ":" + generationIndex;
        }

        private String reasoningDelta(String streamKey, String snapshot) {
            String current = snapshot == null ? "" : snapshot;
            String previous = cumulativeReasoningByStream.put(streamKey, current);
            if (previous == null || previous.isEmpty()) {
                return current;
            }
            return current.startsWith(previous) ? current.substring(previous.length()) : current;
        }

        void append(ChatClientResponse response) {
            if (response.context() != null) {
                context.get().putAll(response.context());
            }
            ChatResponse chatResponse = response.chatResponse();
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
            if (generation.getMetadata() != null
                    && generation.getMetadata() != ChatGenerationMetadata.NULL) {
                generationMetadata.set(generation.getMetadata());
            }
            AssistantMessage output = generation.getOutput();
            appendText(content.get(), output.getText());
            appendText(reasoningContent.get(), AssistantReasoning.get(output));
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
            AssistantMessage output = AssistantMessage.builder()
                    .content(content.get().toString())
                    .properties(AssistantReasoning.metadataWithReasoning(
                            properties.get(), reasoningContent.get().toString()))
                    .toolCalls(toolCalls.get())
                    .build();
            ChatResponse response = new ChatResponse(
                    List.of(new Generation(output, generationMetadata.get())),
                    responseMetadata.get() == null
                            ? ChatResponseMetadata.builder().build()
                            : responseMetadata.get());
            return ChatClientResponse.builder()
                    .chatResponse(response)
                    .context(context.get())
                    .build();
        }

        private void appendText(StringBuilder builder, String text) {
            if (text != null) {
                builder.append(text);
            }
        }
    }
}
