package com.me.galchat.modelapi;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

@Component
public class ModelApiProbeResponseInterpreter {

    private static final Set<String> REASONING_FIELDS = Set.of(
            "reasoning_content", "reasoning", "thinking",
            "thought", "thought_summary");

    private final ObjectMapper objectMapper;

    public ModelApiProbeResponseInterpreter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChatObservation inspectChat(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root == null ? null : root.get("choices");
            JsonNode first = choices == null || choices.isEmpty()
                    ? null : choices.get(0);
            JsonNode message = first == null ? null : first.get("message");
            boolean valid = message != null && message.isObject()
                    && hasAssistantPayload(message);
            return new ChatObservation(valid,
                    valid && containsReasoning(root));
        } catch (RuntimeException exception) {
            return new ChatObservation(false, false);
        }
    }

    public StreamObservation inspectStream(List<String> lines) {
        int validChunks = 0;
        boolean completed = false;
        boolean reasoning = false;
        if (lines == null) {
            return new StreamObservation(0, false, false);
        }
        for (String line : lines) {
            if (line == null || !line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                if ("[DONE]".equals(data) && validChunks > 0) {
                    completed = true;
                }
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root == null ? null : root.get("choices");
                JsonNode first = choices == null || choices.isEmpty()
                        ? null : choices.get(0);
                JsonNode delta = first == null ? null : first.get("delta");
                if (first == null || delta == null || !delta.isObject()) {
                    continue;
                }
                validChunks++;
                reasoning = reasoning || containsReasoning(root);
                JsonNode finishReason = first.get("finish_reason");
                if (finishReason != null && !finishReason.isNull()
                        && !finishReason.asString().isBlank()) {
                    completed = true;
                }
            } catch (RuntimeException ignored) {
                // A malformed provider event is ignored; other valid events can still prove support.
            }
        }
        return new StreamObservation(validChunks, completed, reasoning);
    }

    public ModelApiCapability inspectToolCall(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root == null ? null : root.get("choices");
            JsonNode first = choices == null || choices.isEmpty()
                    ? null : choices.get(0);
            JsonNode message = first == null ? null : first.get("message");
            JsonNode toolCalls = message == null ? null : message.get("tool_calls");
            if (toolCalls != null && toolCalls.isContainer()
                    && !toolCalls.isEmpty()) {
                return ModelApiCapability.SUPPORTED;
            }
            return ModelApiCapability.INCONCLUSIVE;
        } catch (RuntimeException exception) {
            return ModelApiCapability.INCONCLUSIVE;
        }
    }

    private boolean hasAssistantPayload(JsonNode message) {
        JsonNode content = message.get("content");
        JsonNode toolCalls = message.get("tool_calls");
        return content != null && !content.isNull()
                || toolCalls != null && toolCalls.isContainer();
    }

    private boolean containsReasoning(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (String field : REASONING_FIELDS) {
                JsonNode value = node.get(field);
                if (value != null && !value.isNull()
                        && (!value.isTextual() || !value.asString().isBlank())) {
                    return true;
                }
            }
        }
        if (node.isContainer()) {
            return node.values().stream().anyMatch(this::containsReasoning);
        }
        return false;
    }

    public record ChatObservation(boolean valid, boolean reasoningDetected) {
    }

    public record StreamObservation(
            int validChunkCount,
            boolean completed,
            boolean reasoningDetected) {
    }
}
