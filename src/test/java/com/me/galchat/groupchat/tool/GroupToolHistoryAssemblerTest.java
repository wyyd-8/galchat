package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

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
    void diceReferenceUsesPublicSpecialFormatForEveryCharacter() {
        GroupChatToolCallMapper toolCallMapper = mock(GroupChatToolCallMapper.class);
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupToolHistoryAssembler assembler =
                new GroupToolHistoryAssembler(toolCallMapper, summaryMapper, resultMapper);
        GroupChatMessage message = message(41L, 99L);
        when(toolCallMapper.selectList(any())).thenReturn(List.of(new GroupChatToolCall()
                .setReplyStepId(41L)
                .setToolStepNo(1)
                .setToolCallId("call-dice")
                .setToolName("createDiceRoll")
                .setDiceRollSummaryId(501L)));
        when(summaryMapper.selectBatchIds(any())).thenReturn(List.of(new DiceRollSummary()
                .setId(501L)
                .setReason("哈维攻击邪教徒")
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED)));
        when(resultMapper.selectList(any())).thenReturn(List.of(new DiceRollResult()
                .setSummaryId(501L)
                .setRoundNo(1)
                .setDisplayOrder(1)
                .setReason("斗殴检定")
                .setResultData(new DiceRollResultVO("1D100", List.of(), 34))));

        List<Message> kpHistory = assembler.beforeMessages(
                List.of(message), new GroupActorRef("character", 99L)).get(41L);
        List<Message> investigatorHistory = assembler.beforeMessages(
                List.of(message), new GroupActorRef("character", 11L)).get(41L);

        assertThat(kpHistory).singleElement().isInstanceOf(UserMessage.class);
        assertThat(investigatorHistory).singleElement().isInstanceOf(UserMessage.class);
        assertThat(kpHistory.getFirst().getText())
                .contains("<dice-roll summary-id=\"501\" status=\"COMPLETED\">")
                .contains("哈维攻击邪教徒", "斗殴检定", "1D100", "34")
                .doesNotContain("call-dice", "createDiceRoll");
        assertThat(investigatorHistory.getFirst().getText()).isEqualTo(kpHistory.getFirst().getText());
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
        return new GroupToolHistoryAssembler(
                toolCallMapper,
                mock(DiceRollSummaryMapper.class),
                mock(DiceRollResultMapper.class));
    }

    private GroupChatMessage message(Long replyStepId, Long speakerId) {
        return new GroupChatMessage()
                .setReplyStepId(replyStepId)
                .setSpeakerType("character")
                .setSpeakerId(speakerId);
    }
}
