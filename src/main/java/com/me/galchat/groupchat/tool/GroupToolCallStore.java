package com.me.galchat.groupchat.tool;

import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.service.DiceFollowUpLocator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GroupToolCallStore implements DiceFollowUpLocator {

    private final GroupChatToolCallMapper mapper;

    public GroupToolCallStore(GroupChatToolCallMapper mapper) {
        this.mapper = mapper;
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
    }

    public void bindDiceSummary(Long replyStepId, String toolCallId, Long diceRollSummaryId) {
        mapper.bindDiceSummary(replyStepId, toolCallId, diceRollSummaryId);
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
                mapper.updateById(row);
            }
        }
    }
}
