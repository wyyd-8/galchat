package com.me.galchat.groupchat.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.service.DiceFollowUpLocator;
import com.me.galchat.service.impl.GroupTurnCheckpointService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class GroupToolCallStore implements DiceFollowUpLocator {

    private static final Logger logger = LoggerFactory.getLogger(GroupToolCallStore.class);

    private final GroupChatToolCallMapper mapper;
    private final ObjectMapper objectMapper;
    private final GroupTurnCheckpointService checkpointService;

    public GroupToolCallStore(
            GroupChatToolCallMapper mapper,
            ObjectMapper objectMapper,
            GroupTurnCheckpointService checkpointService) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.checkpointService = checkpointService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveExecution(Long replyStepId, ChatResponse response, ToolExecutionResult result) {
        if (replyStepId == null || response == null || response.getResults() == null) {
            return;
        }
        Integer toolStepNo = mapper.nextToolStepNo(replyStepId);
        int stepNo = toolStepNo == null ? 1 : toolStepNo;
        Map<String, GroupChatToolCall> insertedByCallId = new HashMap<>();
        for (Generation generation : response.getResults()) {
            if (!(generation.getOutput() instanceof AssistantMessage assistantMessage)
                    || assistantMessage.getToolCalls() == null) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                GroupChatToolCall row = new GroupChatToolCall()
                        .setReplyStepId(replyStepId)
                        .setToolStepNo(stepNo)
                        .setToolCallId(toolCall.id())
                        .setToolName(toolCall.name())
                        .setToolArguments(toolCall.arguments());
                mapper.insert(row);
                insertedByCallId.put(toolCall.id(), row);
            }
        }
        if (insertedByCallId.isEmpty() || result == null) {
            return;
        }
        saveResponses(insertedByCallId, result.conversationHistory());
        insertedByCallId.values().stream()
                .filter(call -> call.getDiceRollSummaryId() != null)
                .filter(call -> call.getToolResult() != null)
                .map(GroupChatToolCall::getId)
                .filter(java.util.Objects::nonNull)
                .max(Long::compareTo)
                .ifPresent(toolCallId ->
                        checkpointService.recordToolCommitted(
                                replyStepId, toolCallId));
    }

    public void bindDiceSummary(Long replyStepId, String toolCallId, Long diceRollSummaryId) {
        mapper.bindDiceSummary(replyStepId, toolCallId, diceRollSummaryId);
    }

    public boolean hasExecution(Long replyStepId, String toolName) {
        if (replyStepId == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        return mapper.existsByReplyStepIdAndToolName(replyStepId, toolName);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveSystemDice(
            Long replyStepId, KpDiceToolResult result) {
        if (replyStepId == null || result == null
                || result.summary() == null
                || result.summary().getId() == null) {
            throw new UserRequestException("系统掷骰结果缺少步骤或概要");
        }
        Integer next = mapper.nextToolStepNo(replyStepId);
        GroupChatToolCall row = new GroupChatToolCall()
                .setReplyStepId(replyStepId)
                .setToolStepNo(next == null ? 1 : next)
                .setToolCallId("system-recovery-" + UUID.randomUUID())
                .setToolName("systemUnconsciousRecoveryCon")
                .setToolArguments("{}")
                .setToolResult(writeToolResult(result))
                .setDiceRollSummaryId(result.summary().getId());
        mapper.insert(row);
    }

    public Map<Long, Long> diceSummaryIdsByReplyStepIds(
            Collection<Long> replyStepIds) {
        if (replyStepIds == null || replyStepIds.isEmpty()) {
            return Map.of();
        }
        List<GroupChatToolCall> calls = mapper.selectList(
                new LambdaQueryWrapper<GroupChatToolCall>()
                        .in(GroupChatToolCall::getReplyStepId, replyStepIds)
                        .isNotNull(GroupChatToolCall::getDiceRollSummaryId)
                        .orderByDesc(GroupChatToolCall::getId));
        Map<Long, Long> summaryIds = new LinkedHashMap<>();
        for (GroupChatToolCall call : calls) {
            summaryIds.putIfAbsent(
                    call.getReplyStepId(), call.getDiceRollSummaryId());
        }
        return Map.copyOf(summaryIds);
    }

    @Override
    public Long requireLatestSummaryId(
            Long conversationId, Set<String> compatibleToolNames) {
        if (conversationId == null || compatibleToolNames == null
                || compatibleToolNames.isEmpty()) {
            throw new UserRequestException("后续掷骰定位条件不能为空");
        }
        Long summaryId = mapper.findLatestDiceSummaryId(
                conversationId, compatibleToolNames);
        if (summaryId == null) {
            throw new UserRequestException("找不到当前群聊中兼容的前一次掷骰");
        }
        return summaryId;
    }

    private void saveResponses(Map<String, GroupChatToolCall> insertedByCallId, List<Message> history) {
        if (history == null) {
            return;
        }
        for (Message message : history) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                GroupChatToolCall row = insertedByCallId.get(response.id());
                if (row == null) {
                    continue;
                }
                row.setToolResult(response.responseData());
                if (DiceRollConstant.KP_STATE_TOOL_NAMES.contains(row.getToolName())) {
                    row.setDiceRollSummaryId(readSummaryId(response.responseData()));
                }
                mapper.updateById(row);
            }
        }
    }

    private Long readSummaryId(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            throw new UserRequestException("掷骰工具未返回结构化结果");
        }
        try {
            KpDiceToolResult result = objectMapper.readValue(
                    responseData, KpDiceToolResult.class);
            if (result == null || result.summary() == null
                    || result.summary().getId() == null) {
                throw new UserRequestException("掷骰工具结果缺少概要id");
            }
            return result.summary().getId();
        } catch (JacksonException exception) {
            logger.error(
                    "掷骰工具返回结果解析失败，responseLength={}，responseData={}",
                    responseData.length(), responseData, exception);
            throw new UserRequestException("掷骰工具返回结果无法解析");
        }
    }

    private String writeToolResult(KpDiceToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new UserRequestException("系统掷骰结果无法序列化");
        }
    }
}
