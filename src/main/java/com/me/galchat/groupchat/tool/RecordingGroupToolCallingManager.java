package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.utils.CurrentHolder;
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
        Integer previousUserId = CurrentHolder.getCurrentId();
        Integer executionUserId = executionUserId(prompt);
        if (executionUserId != null) {
            CurrentHolder.setCurrentId(executionUserId);
        }
        try {
            return executeToolCallsWithContext(prompt, response);
        } finally {
            if (executionUserId != null) {
                if (previousUserId == null) {
                    CurrentHolder.remove();
                } else {
                    CurrentHolder.setCurrentId(previousUserId);
                }
            }
        }
    }

    private ToolExecutionResult executeToolCallsWithContext(
            Prompt prompt, ChatResponse response) {
        List<String> toolNames = toolNames(response);
        long diceToolCount = toolNames.stream()
                .filter(DiceRollConstant.KP_STATE_TOOL_NAMES::contains)
                .count();
        boolean finishMarker = toolNames.contains(
                "markCombatFinished");
        boolean clarification = toolNames.contains(
                "askForClarification");
        if (diceToolCount > 0 && toolNames.size() != 1) {
            throw new UserRequestException("一次响应只能调用一个掷骰工具，且不能与其他工具并行");
        }
        if (finishMarker && diceToolCount > 0) {
            throw new UserRequestException(
                    "结束战斗标记不能与掷骰工具并行调用");
        }
        if (clarification && toolNames.size() != 1) {
            throw new UserRequestException(
                    "追问工具必须单独调用");
        }
        Long replyStepId = replyStepId(prompt);
        if (toolNames.contains("updateWeaponState")
                && store.hasExecution(
                        replyStepId,
                        DiceRollConstant.TOOL_REQUEST_FIREARM_ATTACK)) {
            throw new UserRequestException(
                    "枪械攻击工具已自动更新武器状态，不能在同一裁定步骤重复覆盖");
        }
        if (diceToolCount == 1 || clarification) {
            return transactionTemplate.execute(status ->
                    executeAndRecord(prompt, response, replyStepId));
        }
        if (finishMarker && toolNames.size() == 1) {
            return delegate.executeToolCalls(prompt, response);
        }
        return executeAndRecord(prompt, response, replyStepId);
    }

    private Integer executionUserId(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions
                toolCallingOptions)) {
            return null;
        }
        Map<String, Object> context =
                toolCallingOptions.getToolContext();
        if (context == null) {
            return null;
        }
        Long userId = TypeConvertUtils.asLong(
                context.get(ChatToolContextConstant.USER_ID_KEY));
        return userId == null ? null : Math.toIntExact(userId);
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
