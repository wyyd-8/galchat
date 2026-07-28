package com.me.galchat.groupchat.tool;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupToolHistoryAssemblerTest {

    @Test
    void standardToolHistoryIsVisibleOnlyToReplyStepOwner() {
        GroupChatToolCallMapper toolCallMapper = mock(GroupChatToolCallMapper.class);
        GroupToolHistoryAssembler assembler = assembler(toolCallMapper);
        GroupChatMessage message = message(41L, 11L);
        when(toolCallMapper.selectList(any())).thenReturn(List.of(new GroupChatToolCall()
                .setReplyStepId(41L)
                .setToolStepNo(1)
                .setToolCallId("call-1")
                .setToolName("searchInfo")
                .setToolArguments("{\"query\":\"旧宅\"}")
                .setToolResult("旧宅位于河边")));

        Map<Long, List<Message>> ownerHistory = assembler.beforeMessages(
                List.of(message), new GroupActorRef("character", 11L));
        Map<Long, List<Message>> otherHistory = assembler.beforeMessages(
                List.of(message), new GroupActorRef("character", 12L));

        assertThat(ownerHistory.get(41L))
                .hasSize(2)
                .anyMatch(AssistantMessage.class::isInstance)
                .anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(otherHistory.get(41L)).isEmpty();
    }

    @Test
    void diceToolCallIsOmittedFromPrivateToolReplay() {
        GroupChatToolCallMapper toolCallMapper = mock(GroupChatToolCallMapper.class);
        GroupToolHistoryAssembler assembler = assembler(toolCallMapper);
        GroupChatMessage message = message(41L, 99L);
        when(toolCallMapper.selectList(any())).thenReturn(List.of(new GroupChatToolCall()
                .setReplyStepId(41L)
                .setToolStepNo(1)
                .setToolCallId("call-dice")
                .setToolName("createDiceRoll")
                .setDiceRollSummaryId(501L)));

        List<Message> kpHistory = assembler.beforeMessages(
                List.of(message), new GroupActorRef("character", 99L)).get(41L);
        List<Message> investigatorHistory = assembler.beforeMessages(
                List.of(message), new GroupActorRef("character", 11L)).get(41L);

        assertThat(kpHistory).isEmpty();
        assertThat(investigatorHistory).isEmpty();
    }

    @Test
    void kpOwnsItsPrivateToolHistoryEvenThoughItsIdIsNull() {
        GroupChatToolCallMapper toolCallMapper = mock(GroupChatToolCallMapper.class);
        GroupToolHistoryAssembler assembler = assembler(toolCallMapper);
        GroupChatMessage kpMessage = message(41L, null).setSpeakerType("kp");
        when(toolCallMapper.selectList(any())).thenReturn(List.of(new GroupChatToolCall()
                .setReplyStepId(41L)
                .setToolStepNo(1)
                .setToolCallId("call-1")
                .setToolName("searchInfo")
                .setToolArguments("{\"query\":\"旧宅\"}")
                .setToolResult("旧宅位于河边")));

        Map<Long, List<Message>> own = assembler.beforeMessages(
                List.of(kpMessage), new GroupActorRef("kp", null));
        Map<Long, List<Message>> other = assembler.beforeMessages(
                List.of(kpMessage), new GroupActorRef("character", null));

        assertThat(own.get(41L)).hasSize(2);
        assertThat(other.get(41L)).isEmpty();
    }

    private GroupToolHistoryAssembler assembler(GroupChatToolCallMapper toolCallMapper) {
        return new GroupToolHistoryAssembler(toolCallMapper);
    }

    private GroupChatMessage message(Long replyStepId, Long speakerId) {
        return new GroupChatMessage()
                .setReplyStepId(replyStepId)
                .setSpeakerType("character")
                .setSpeakerId(speakerId);
    }
}
