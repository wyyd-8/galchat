package com.me.galchat.groupchat.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GroupToolHistoryAssembler {

    private final GroupChatToolCallMapper toolCallMapper;

    public GroupToolHistoryAssembler(GroupChatToolCallMapper toolCallMapper) {
        this.toolCallMapper = toolCallMapper;
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

}
