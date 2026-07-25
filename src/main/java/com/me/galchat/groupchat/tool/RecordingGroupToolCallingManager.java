package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.utils.TypeConvertUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

public class RecordingGroupToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final GroupToolCallStore store;

    public RecordingGroupToolCallingManager(ToolCallingManager delegate, GroupToolCallStore store) {
        this.delegate = delegate;
        this.store = store;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        ToolExecutionResult result = delegate.executeToolCalls(prompt, response);
        Long replyStepId = replyStepId(prompt);
        if (replyStepId != null) {
            store.saveExecution(replyStepId, response, result);
        }
        return result;
    }

    private Long replyStepId(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions toolCallingOptions)) {
            return null;
        }
        Map<String, Object> context = toolCallingOptions.getToolContext();
        if (context == null) {
            return null;
        }
        return TypeConvertUtils.asLong(context.get(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
    }
}
