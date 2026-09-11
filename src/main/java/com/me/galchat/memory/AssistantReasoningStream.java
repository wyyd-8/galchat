package com.me.galchat.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.HashMap;
import java.util.Map;

/** Converts provider-specific reasoning streams into appendable deltas. */
public final class AssistantReasoningStream {

    private final Map<String, String> cumulativeSnapshots = new HashMap<>();

    public String delta(
            ChatResponse response,
            AssistantMessage output,
            int generationIndex) {
        String reasoning = AssistantReasoning.get(output);
        if (!AssistantReasoning.isCumulativeSnapshot(output)) {
            return reasoning;
        }

        String current = reasoning == null ? "" : reasoning;
        String previous = cumulativeSnapshots.put(
                streamKey(response, output, generationIndex), current);
        if (previous == null || previous.isEmpty()) {
            return current;
        }
        return current.startsWith(previous)
                ? current.substring(previous.length()) : current;
    }

    public void reset() {
        cumulativeSnapshots.clear();
    }

    private String streamKey(
            ChatResponse response,
            AssistantMessage output,
            int generationIndex) {
        Object outputId = output.getMetadata().get("id");
        String responseId = response.getMetadata() == null
                ? null : response.getMetadata().getId();
        Object streamId = outputId == null ? responseId : outputId;
        return (streamId == null ? "default" : streamId.toString())
                + ":" + generationIndex;
    }
}
