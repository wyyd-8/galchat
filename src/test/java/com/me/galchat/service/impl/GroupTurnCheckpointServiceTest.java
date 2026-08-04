package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupTurnCheckpoint;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupTurnCheckpointMapper;
import com.me.galchat.service.ICharacterCardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroupTurnCheckpointServiceTest {

    @Test
    void invalidTurnStepContextCannotTriggerDestructiveRecovery() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        fixture.step().setTurnId(999L);

        assertThatThrownBy(() -> fixture.service().restore(
                fixture.turn(), fixture.step()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("行动轮检查点上下文不完整");

        verifyNoInteractions(
                fixture.messageMapper(), fixture.toolCallMapper());
    }

    @Test
    void missingCheckpointRestartsTheWholeTurn() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        GroupChatReplyStep completed = new GroupChatReplyStep()
                .setId(102L)
                .setTurnId(101L)
                .setStepNo(1)
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setOutputMessageId(201L);
        GroupChatReplyStep failed = fixture.step()
                .setStepNo(2)
                .setErrorMessage("模型调用失败");
        when(fixture.checkpointMapper().selectById(7L))
                .thenReturn(null);
        when(fixture.stepMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(completed, failed));

        boolean checkpointRestored = fixture.service().restore(
                fixture.turn(), failed);

        assertThat(checkpointRestored).isFalse();
        assertThat(List.of(completed, failed))
                .allSatisfy(step -> {
                    assertThat(step.getStatus())
                            .isEqualTo(GroupChatConstant.STATUS_PENDING);
                    assertThat(step.getOutputMessageId()).isNull();
                    assertThat(step.getErrorMessage()).isNull();
                });
        assertThat(fixture.turn().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_RUNNING);
        verify(fixture.toolCallMapper()).delete(
                org.mockito.ArgumentMatchers.any());
        verify(fixture.messageMapper()).delete(
                org.mockito.ArgumentMatchers.any());
        verify(fixture.stepMapper()).updateById(completed);
        verify(fixture.stepMapper()).updateById(failed);
        verify(fixture.turnMapper()).updateById(fixture.turn());
        verify(fixture.checkpointMapper()).deleteById(7L);
    }

    @Test
    void restoresOnlyTheFailedStepTailAfterPausedCheckpoint() {
        GroupTurnCheckpointMapper checkpointMapper =
                mock(GroupTurnCheckpointMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatToolCallMapper toolCallMapper =
                mock(GroupChatToolCallMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupTurnCheckpointService service =
                new GroupTurnCheckpointService(
                        checkpointMapper,
                        messageMapper,
                        toolCallMapper,
                        stepMapper,
                        turnMapper,
                        mock(DiceRollSummaryMapper.class),
                        mock(com.me.galchat.groupchat.dice
                                .DiceRollMessageCodec.class),
                        mock(ICharacterCardService.class),
                        JsonMapper.builder().build());
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(103L)
                .setTurnId(101L)
                .setOutputMessageId(203L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        when(checkpointMapper.selectById(7L)).thenReturn(
                new GroupTurnCheckpoint()
                        .setConversationId(7L)
                        .setTurnId(101L)
                        .setReplyStepId(103L)
                        .setCheckpointType(
                                GroupTurnCheckpointService.PAUSED)
                        .setMessageId(202L)
                        .setToolCallId(9L));

        boolean restored = service.restore(turn, step);

        assertThat(restored).isTrue();
        assertThat(step.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        assertThat(step.getOutputMessageId()).isNull();
        assertThat(turn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_RUNNING);
        verify(messageMapper).deleteAfterCheckpoint(103L, 202L);
        verify(toolCallMapper).deleteAfterCheckpoint(103L, 9L);
        verify(stepMapper).updateById(step);
        verify(turnMapper).updateById(turn);
    }

    @Test
    void committedPendingDiceRestoresTheWaitingBoundary() {
        Fixture fixture = fixture(
                GroupTurnCheckpointService.TOOL_COMMITTED);
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(committedDiceCall()), List.of());
        when(fixture.diceRollSummaryMapper().selectById(501L))
                .thenReturn(new DiceRollSummary()
                        .setId(501L)
                        .setStatus(DiceRollConstant.STATUS_PENDING));
        GroupChatMessage message = new GroupChatMessage()
                .setId(203L)
                .setReplyStepId(103L)
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        when(fixture.messageMapper().selectById(203L))
                .thenReturn(message);
        when(fixture.diceMessageCodec().encode(501L, List.of(1)))
                .thenReturn("{\"summaryId\":501,\"roundNos\":[1]}");

        boolean restored = fixture.service().restore(
                fixture.turn(), fixture.step());

        assertThat(restored).isTrue();
        assertThat(fixture.step().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(fixture.turn().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(message.getMessageKind())
                .isEqualTo(GroupChatConstant.MESSAGE_DICE_ROLL);
        assertThat(message.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        assertThat(message.getContent())
                .isEqualTo("{\"summaryId\":501,\"roundNos\":[1]}");
    }

    @Test
    void committedCompletedDiceResumesTheSameStep() {
        Fixture fixture = fixture(
                GroupTurnCheckpointService.WAITING_DICE);
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(committedDiceCall()), List.of());
        when(fixture.diceRollSummaryMapper().selectById(501L))
                .thenReturn(new DiceRollSummary()
                        .setId(501L)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));

        boolean restored = fixture.service().restore(
                fixture.turn(), fixture.step());

        assertThat(restored).isTrue();
        assertThat(fixture.step().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        assertThat(fixture.turn().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_RUNNING);
    }

    @Test
    void recordsTheLatestMessageAndToolBoundaryForTheActiveStep() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        when(fixture.toolCallMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(9L);
        when(fixture.messageMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(212L);
        GroupTurnCheckpointMapper checkpointMapper =
                fixture.checkpointMapper();

        fixture.service().recordBoundary(
                fixture.turn(), fixture.step(),
                GroupTurnCheckpointService.PAUSED);

        ArgumentCaptor<GroupTurnCheckpoint> saved =
                ArgumentCaptor.forClass(GroupTurnCheckpoint.class);
        verify(checkpointMapper).upsert(saved.capture());
        assertThat(saved.getValue())
                .extracting(
                        GroupTurnCheckpoint::getConversationId,
                        GroupTurnCheckpoint::getTurnId,
                        GroupTurnCheckpoint::getReplyStepId,
                        GroupTurnCheckpoint::getCheckpointType,
                        GroupTurnCheckpoint::getMessageId,
                        GroupTurnCheckpoint::getToolCallId)
                .containsExactly(
                        7L, 101L, 103L,
                        GroupTurnCheckpointService.PAUSED,
                        212L, 9L);
    }

    @Test
    void committedToolRecordsBothDurableHighWaterMarks() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        when(fixture.stepMapper().selectById(103L))
                .thenReturn(fixture.step());
        when(fixture.turnMapper().selectById(101L))
                .thenReturn(fixture.turn());
        when(fixture.messageMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(212L);

        fixture.service().recordToolCommitted(103L, 9L);

        ArgumentCaptor<GroupTurnCheckpoint> saved =
                ArgumentCaptor.forClass(GroupTurnCheckpoint.class);
        verify(fixture.checkpointMapper()).upsert(saved.capture());
        assertThat(saved.getValue())
                .extracting(
                        GroupTurnCheckpoint::getCheckpointType,
                        GroupTurnCheckpoint::getMessageId,
                        GroupTurnCheckpoint::getToolCallId)
                .containsExactly(
                        GroupTurnCheckpointService.TOOL_COMMITTED,
                        212L, 9L);
    }

    @Test
    void rollsBackSuccessfulAttributeChangeBeforeDeletingFailedStepTail() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        GroupChatToolCall adjustment = new GroupChatToolCall()
                .setId(10L)
                .setReplyStepId(103L)
                .setToolName("adjustBasicAttributes")
                .setToolResult("""
                        {"characterName":"林恩",
                         "changes":{"STR":{"before":50,"after":60}},
                         "damageBonus":"0","build":0}
                        """);
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(adjustment));

        fixture.service().restore(fixture.turn(), fixture.step());

        ArgumentCaptor<KpCharacterAttributeDTOs.Result> result =
                ArgumentCaptor.forClass(
                        KpCharacterAttributeDTOs.Result.class);
        var ordered = org.mockito.Mockito.inOrder(
                fixture.characterCardService(),
                fixture.toolCallMapper());
        ordered.verify(fixture.characterCardService())
                .rollbackBasicAttributeAdjustment(
                        org.mockito.ArgumentMatchers.eq(7L),
                        result.capture());
        ordered.verify(fixture.toolCallMapper())
                .deleteAfterCheckpoint(103L, 9L);
        assertThat(result.getValue().characterName()).isEqualTo("林恩");
        assertThat(result.getValue().changes().get("STR"))
                .isEqualTo(new KpCharacterAttributeDTOs.ValueChange(50, 60));
    }

    private Fixture fixture(String checkpointType) {
        GroupTurnCheckpointMapper checkpointMapper =
                mock(GroupTurnCheckpointMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatToolCallMapper toolCallMapper =
                mock(GroupChatToolCallMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        DiceRollSummaryMapper diceRollSummaryMapper =
                mock(DiceRollSummaryMapper.class);
        com.me.galchat.groupchat.dice.DiceRollMessageCodec codec =
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class);
        ICharacterCardService characterCardService =
                mock(ICharacterCardService.class);
        GroupTurnCheckpointService service =
                new GroupTurnCheckpointService(
                        checkpointMapper,
                        messageMapper,
                        toolCallMapper,
                        stepMapper,
                        turnMapper,
                        diceRollSummaryMapper,
                        codec,
                        characterCardService,
                        JsonMapper.builder().build());
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(103L)
                .setTurnId(101L)
                .setOutputMessageId(203L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        when(checkpointMapper.selectById(7L)).thenReturn(
                new GroupTurnCheckpoint()
                        .setConversationId(7L)
                        .setTurnId(101L)
                        .setReplyStepId(103L)
                        .setCheckpointType(checkpointType)
                        .setMessageId(202L)
                        .setToolCallId(9L));
        return new Fixture(
                service, checkpointMapper, messageMapper, toolCallMapper,
                stepMapper, turnMapper, diceRollSummaryMapper, codec,
                characterCardService,
                turn, step);
    }

    private GroupChatToolCall committedDiceCall() {
        return new GroupChatToolCall()
                .setId(9L)
                .setReplyStepId(103L)
                .setToolName("requestCheck")
                .setDiceRollSummaryId(501L)
                .setToolResult("""
                        {"summary":{"id":501,"status":"PENDING"},
                         "results":[{"roundNo":1}]}
                        """);
    }

    private record Fixture(
            GroupTurnCheckpointService service,
            GroupTurnCheckpointMapper checkpointMapper,
            GroupChatMessageMapper messageMapper,
            GroupChatToolCallMapper toolCallMapper,
            GroupChatReplyStepMapper stepMapper,
            GroupChatTurnMapper turnMapper,
            DiceRollSummaryMapper diceRollSummaryMapper,
            com.me.galchat.groupchat.dice.DiceRollMessageCodec
                    diceMessageCodec,
            ICharacterCardService characterCardService,
            GroupChatTurn turn,
            GroupChatReplyStep step) {
    }
}
