package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.utils.TypeConvertUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecordingGroupToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final GroupToolCallStore store;
    private final TransactionTemplate transactionTemplate;

    public RecordingGroupToolCallingManager(
            ToolCallingManager delegate,
            GroupToolCallStore store,
            TransactionTemplate transactionTemplate) {
        this.delegate = delegate;
        this.store = store;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        List<String> toolNames = toolNames(response);
        long diceToolCount = toolNames.stream()
                .filter(DiceRollConstant.KP_STATE_TOOL_NAMES::contains)
                .count();
        if (diceToolCount > 0 && toolNames.size() != 1) {
            throw new UserRequestException("一次响应只能调用一个掷骰工具，且不能与其他工具并行");
        }
        Long replyStepId = replyStepId(prompt);
        if (diceToolCount == 1) {
            return transactionTemplate.execute(status ->
                    executeAndRecord(prompt, response, replyStepId));
        }
        return executeAndRecord(prompt, response, replyStepId);
    }

    private ToolExecutionResult executeAndRecord(
            Prompt prompt, ChatResponse response, Long replyStepId) {
        ToolExecutionResult result = delegate.executeToolCalls(prompt, response);
        if (replyStepId != null) {
            store.saveExecution(replyStepId, response, result);
        }
        return result;
    }

    private List<String> toolNames(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        response.getResults().forEach(generation -> {
            if (generation.getOutput() instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null) {
                assistant.getToolCalls().forEach(call -> names.add(call.name()));
            }
        });
        return names;
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
