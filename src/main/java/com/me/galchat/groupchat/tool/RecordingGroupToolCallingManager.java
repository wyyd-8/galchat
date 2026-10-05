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
        validateChildSceneBatch(response);
        List<String> toolNames = toolNames(response);
        long diceToolCount = toolNames.stream()
                .filter(DiceRollConstant.KP_STATE_TOOL_NAMES::contains)
                .count();
        boolean finishMarker = toolNames.contains(
                "markCombatFinished");
        boolean clarification = toolNames.contains(
                "askForClarification")
                || toolNames.contains("askKp");
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
        if (toolNames.stream().anyMatch(com.me.galchat.service.impl.trpg.TrpgToolStateRecoveryService.TOOLS::contains)
                || toolNames.contains("publishExplorationScenes")
                || diceToolCount == 1 || clarification
                || toolNames.contains("startCombat") || finishMarker
                || toolNames.contains("adjustBasicAttributes")
                || toolNames.contains("purchaseEquipment")
                || toolNames.contains("suspendInvestigators")
                || toolNames.contains("resumeSuspendedInvestigators")
                || toolNames.contains("finishSceneExploration")
                || toolNames.contains("endSceneExploration")
                || toolNames.contains("resumeWaitingInvestigators")
                || toolNames.contains("showMaterial")) {
            return transactionTemplate.execute(status ->
                    executeAndRecord(prompt, response, replyStepId));
        }
        return executeAndRecord(prompt, response, replyStepId);
    }

    private void validateChildSceneBatch(ChatResponse response) {
        if (response == null || response.getResults() == null) return;
        var calls = response.getResults().stream()
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .filter(call -> "startChildScene".equals(call.name())).toList();
        if (calls.size() < 2) return;
        var scenes = new java.util.HashSet<String>();
        var investigators = new java.util.HashSet<String>();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        for (var call : calls) {
            ChildSceneArguments args;
            try {
                args = json.readValue(call.arguments(), ChildSceneArguments.class);
            } catch (tools.jackson.core.JacksonException exception) {
                throw new UserRequestException("子场景参数无法解析");
            }
            if (args == null || args.childSceneName() == null || args.investigatorNames() == null) {
                throw new UserRequestException("子场景参数不完整");
            }
            if (!scenes.add(args.childSceneName().trim())) {
                throw new UserRequestException("同一目的地只应调用一次子场景工具");
            }
            for (String name : args.investigatorNames()) {
                if (name == null || !investigators.add(name.trim())) {
                    throw new UserRequestException("同一调查员不能在本批调用中前往多个子场景");
                }
            }
        }
    }

    private record ChildSceneArguments(String childSceneName, List<String> investigatorNames) {}

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
