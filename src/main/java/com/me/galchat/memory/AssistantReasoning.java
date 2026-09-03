package com.me.galchat.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.HashMap;
import java.util.Map;

/** Provider-neutral access to optional assistant reasoning output. */
public final class AssistantReasoning {

    public static final String METADATA_KEY = "reasoningContent";

    private AssistantReasoning() {
    }

    public static String get(AssistantMessage message) {
        if (message instanceof DeepSeekAssistantMessage deepSeekMessage) {
            String reasoning = deepSeekMessage.getReasoningContent();
            if (reasoning != null && !reasoning.isEmpty()) {
                return reasoning;
            }
        }
        Object value = message.getMetadata().get(METADATA_KEY);
        return value instanceof String text ? text : null;
    }

    /** OpenAI-compatible responses expose reasoning metadata as a cumulative stream snapshot. */
    public static boolean isCumulativeSnapshot(AssistantMessage message) {
        return !(message instanceof DeepSeekAssistantMessage)
                && message.getMetadata().get(METADATA_KEY) instanceof String;
    }

    public static Map<String, Object> metadataWithReasoning(
            Map<String, Object> metadata, String reasoning) {
        Map<String, Object> result = new HashMap<>();
        if (metadata != null) {
            result.putAll(metadata);
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            result.put(METADATA_KEY, reasoning);
        }
        return result;
    }
}
