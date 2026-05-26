package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.memory.UserChatMemory;
import com.me.galchat.service.ChatToolEventListener;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class RecordingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final UserChatMemory userChatMemory;

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        emitToolEvent(prompt);
        ToolExecutionResult result = delegate.executeToolCalls(prompt, response);
        ConversationContext conversationContext = resolveConversationContext(prompt);
        if (conversationContext == null) {
            return result;
        }

        userChatMemory.saveToolExecution(conversationContext.conversationInfo(), conversationContext.userMessageId(),
                response, result);
        return result;
    }

    private ConversationContext resolveConversationContext(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions toolCallingOptions)) {
            return null;
        }

        Map<String, Object> toolContext = toolCallingOptions.getToolContext();
        if (toolContext == null || toolContext.isEmpty()) {
            return null;
        }

        Long userWorldId = asLong(toolContext.get(ChatToolContextConstant.USER_WORLD_ID_KEY));
        Long characterId = asLong(toolContext.get(ChatToolContextConstant.CHARACTER_ID_KEY));
        Long userMessageId = asLong(toolContext.get(ChatToolContextConstant.USER_MESSAGE_ID_KEY));
        if (userWorldId == null || characterId == null || userMessageId == null) {
            return null;
        }

        return new ConversationContext(new ConversationInfo(userWorldId, characterId, null), userMessageId);
    }

    private Long asLong(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return Long.valueOf(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void emitToolEvent(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions toolCallingOptions)) {
            return;
        }

        Map<String, Object> toolContext = toolCallingOptions.getToolContext();
        if (toolContext == null) {
            return;
        }

        Object listener = toolContext.get(ChatToolContextConstant.TOOL_EVENT_LISTENER_KEY);
        if (listener instanceof ChatToolEventListener toolEventListener) {
            toolEventListener.onToolCall();
        }
    }

    private record ConversationContext(ConversationInfo conversationInfo, Long userMessageId) {
    }
}
