package com.me.galchat.groupchat.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GroupToolHistoryAssembler {

    private final GroupChatToolCallMapper toolCallMapper;
    private final DiceRollSummaryMapper diceSummaryMapper;
    private final DiceRollResultMapper diceResultMapper;

    public GroupToolHistoryAssembler(GroupChatToolCallMapper toolCallMapper,
                                     DiceRollSummaryMapper diceSummaryMapper,
                                     DiceRollResultMapper diceResultMapper) {
        this.toolCallMapper = toolCallMapper;
        this.diceSummaryMapper = diceSummaryMapper;
        this.diceResultMapper = diceResultMapper;
    }

    public Map<Long, List<Message>> beforeMessages(
            List<GroupChatMessage> messages, GroupActorRef currentActor) {
        Map<Long, GroupActorRef> ownerByStep = new LinkedHashMap<>();
        for (GroupChatMessage message : messages) {
            if (message.getReplyStepId() != null) {
                ownerByStep.putIfAbsent(message.getReplyStepId(),
                        new GroupActorRef(message.getSpeakerType(), message.getSpeakerId()));
            }
        }
        if (ownerByStep.isEmpty()) {
            return Map.of();
        }

        List<GroupChatToolCall> calls = toolCallMapper.selectList(
                new LambdaQueryWrapper<GroupChatToolCall>()
                        .in(GroupChatToolCall::getReplyStepId, ownerByStep.keySet())
                        .orderByAsc(GroupChatToolCall::getReplyStepId)
                        .orderByAsc(GroupChatToolCall::getToolStepNo)
                        .orderByAsc(GroupChatToolCall::getId));
        Map<Long, DiceRollSummary> summaries = diceSummaries(calls);
        Map<Long, List<DiceRollResult>> results = diceResults(summaries.keySet());
        Map<Long, List<GroupChatToolCall>> callsByStep = calls.stream()
                .collect(Collectors.groupingBy(
                        GroupChatToolCall::getReplyStepId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<Long, List<Message>> assembled = new LinkedHashMap<>();
        for (Map.Entry<Long, GroupActorRef> entry : ownerByStep.entrySet()) {
            Long replyStepId = entry.getKey();
            List<Message> before = new ArrayList<>();
            Map<Integer, List<GroupChatToolCall>> callsByModelStep =
                    callsByStep.getOrDefault(replyStepId, List.of()).stream()
                            .collect(Collectors.groupingBy(
                                    GroupChatToolCall::getToolStepNo,
                                    LinkedHashMap::new,
                                    Collectors.toList()));
            for (List<GroupChatToolCall> modelStepCalls : callsByModelStep.values()) {
                if (entry.getValue().matches(currentActor.type(), currentActor.id())) {
                    appendPrivateToolMessages(before, modelStepCalls);
                }
                for (GroupChatToolCall call : modelStepCalls) {
                    if (call.getDiceRollSummaryId() != null) {
                        before.add(new UserMessage(formatDice(
                                summaries.get(call.getDiceRollSummaryId()),
                                results.getOrDefault(call.getDiceRollSummaryId(), List.of()))));
                    }
                }
            }
            assembled.put(replyStepId, List.copyOf(before));
        }
        return assembled;
    }

    private void appendPrivateToolMessages(List<Message> messages, List<GroupChatToolCall> calls) {
        List<GroupChatToolCall> privateCalls = calls.stream()
                .filter(call -> call.getDiceRollSummaryId() == null)
                .toList();
        if (privateCalls.isEmpty()) {
            return;
        }
        messages.add(AssistantMessage.builder()
                .content("")
                .toolCalls(privateCalls.stream()
                        .map(call -> new AssistantMessage.ToolCall(
                                call.getToolCallId(), "function", call.getToolName(), call.getToolArguments()))
                        .toList())
                .build());
        List<ToolResponseMessage.ToolResponse> responses = privateCalls.stream()
                .filter(call -> call.getToolResult() != null)
                .map(call -> new ToolResponseMessage.ToolResponse(
                        call.getToolCallId(), call.getToolName(), call.getToolResult()))
                .toList();
        if (!responses.isEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
    }

    private Map<Long, DiceRollSummary> diceSummaries(List<GroupChatToolCall> calls) {
        Set<Long> ids = calls.stream()
                .map(GroupChatToolCall::getDiceRollSummaryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return diceSummaryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(DiceRollSummary::getId, summary -> summary));
    }

    private Map<Long, List<DiceRollResult>> diceResults(Set<Long> summaryIds) {
        if (summaryIds.isEmpty()) {
            return Map.of();
        }
        return diceResultMapper.selectList(new LambdaQueryWrapper<DiceRollResult>()
                        .in(DiceRollResult::getSummaryId, summaryIds)
                        .orderByAsc(DiceRollResult::getSummaryId)
                        .orderByAsc(DiceRollResult::getRoundNo)
                        .orderByAsc(DiceRollResult::getDisplayOrder)
                        .orderByAsc(DiceRollResult::getId))
                .stream()
                .collect(Collectors.groupingBy(
                        DiceRollResult::getSummaryId,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private String formatDice(DiceRollSummary summary, List<DiceRollResult> results) {
        if (summary == null) {
            return "<dice-roll unavailable=\"true\" />";
        }
        StringBuilder content = new StringBuilder()
                .append("<dice-roll summary-id=\"").append(summary.getId())
                .append("\" status=\"").append(summary.getStatus()).append("\">\n")
                .append("原因：").append(summary.getReason()).append('\n')
                .append("轮数：").append(summary.getRoundCount());
        if (StringUtils.hasText(summary.getTotalResult())) {
            content.append("\n累计结果：").append(summary.getTotalResult());
        }
        for (DiceRollResult result : results) {
            DiceRollResultVO data = result.getResultData();
            content.append("\n- 第").append(result.getRoundNo()).append("轮 ")
                    .append(result.getReason()).append("：");
            if (data != null) {
                content.append(data.getFormula()).append(" = ")
                        .append(data.getResult() == null ? "待掷骰" : data.getResult());
            }
        }
        return content.append("\n</dice-roll>").toString();
    }
}
