package com.me.galchat.service.impl.group;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupTurnRecoveryServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "trpg_scene_action,waiting_interaction", "combat_adjudicate,waiting_interaction",
            "combat_reaction_route,completed", "combat_reaction_route,running"
    })
    void crashAfterCommittedQuestionKeepsAnswerRetryable(String action, String status) {
        var checkpoints = mock(com.me.galchat.mapper.GroupTurnCheckpointMapper.class);
        var turns = mock(GroupChatTurnMapper.class);
        var steps = mock(GroupChatReplyStepMapper.class);
        var messages = mock(GroupChatMessageMapper.class);
        var service = new GroupTurnRecoveryService(checkpoints, turns, steps, messages);
        when(turns.selectList(any())).thenReturn(List.of(new GroupChatTurn().setId(10L)
                .setStatus(GroupChatConstant.STATUS_RUNNING)));
        when(checkpoints.selectById(7L)).thenReturn(new com.me.galchat.domain.po.GroupTurnCheckpoint()
                .setTurnId(10L).setReplyStepId(12L).setCheckpointType("INTERACTION_COMMITTED"));
        var source = new GroupChatReplyStep().setId(12L).setTurnId(10L).setStepNo(2)
                .setActionType(action).setStatus(status);
        when(steps.selectById(12L)).thenReturn(source);

        service.recoverInterrupted(7L);

        assertThat(source.getStatus()).isEqualTo(GroupChatConstant.STATUS_FAILED);
        verify(steps).updateById(source);
        var writes = org.mockito.ArgumentCaptor.forClass(GroupChatReplyStep.class);
        verify(steps, times(2)).update(writes.capture(), any(Wrapper.class));
        assertThat(writes.getAllValues()).extracting(GroupChatReplyStep::getStatus)
                .containsExactly(GroupChatConstant.STATUS_FAILED, GroupChatConstant.STATUS_BLOCKED);
    }

    @Test
    void recoveryFailsRunningDataAndCancelsPendingSteps() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper = mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupTurnRecoveryService service = new GroupTurnRecoveryService(mock(com.me.galchat.mapper.GroupTurnCheckpointMapper.class),
                turnMapper, stepMapper, messageMapper);
        when(turnMapper.selectList(any())).thenReturn(List.of(
                new GroupChatTurn().setId(10L).setStatus(GroupChatConstant.STATUS_RUNNING)));

        service.recoverInterrupted(7L);

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(GroupChatMessage.class);
        verify(messageMapper).update(messageCaptor.capture(), any(Wrapper.class));
        assertThat(messageCaptor.getValue().getStatus()).isEqualTo(GroupChatConstant.STATUS_FAILED);

        var stepCaptor = org.mockito.ArgumentCaptor.forClass(GroupChatReplyStep.class);
        verify(stepMapper, times(2)).update(stepCaptor.capture(), any(Wrapper.class));
        assertThat(stepCaptor.getAllValues()).extracting(GroupChatReplyStep::getStatus)
                .containsExactly(GroupChatConstant.STATUS_FAILED, GroupChatConstant.STATUS_CANCELLED);

        var turnCaptor = org.mockito.ArgumentCaptor.forClass(GroupChatTurn.class);
        verify(turnMapper).update(turnCaptor.capture(), any(Wrapper.class));
        assertThat(turnCaptor.getValue().getStatus()).isEqualTo(GroupChatConstant.STATUS_FAILED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"trpg_scene_action", "trpg_summary", "trpg_run_scene_close", "trpg_turn_finalize"})
    void recoveryBlocksTailAfterInterruptedRetryableStep(String actionType) {
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupTurnRecoveryService service =
                new GroupTurnRecoveryService(mock(com.me.galchat.mapper.GroupTurnCheckpointMapper.class),
                        turnMapper,
                        stepMapper,
                        mock(GroupChatMessageMapper.class));
        when(turnMapper.selectList(any())).thenReturn(List.of(
                new GroupChatTurn()
                        .setId(10L)
                        .setStatus(
                                GroupChatConstant.STATUS_RUNNING)));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                new GroupChatReplyStep()
                        .setId(12L)
                        .setTurnId(10L)
                        .setStepNo(2)
                        .setActionType(
                                actionType)
                        .setSpeakerType(
                                GroupChatConstant.ACTOR_CHARACTER)
                        .setStatus(
                                GroupChatConstant.STATUS_RUNNING)));

        service.recoverInterrupted(7L);

        var captor = org.mockito.ArgumentCaptor.forClass(
                GroupChatReplyStep.class);
        verify(stepMapper, times(2))
                .update(captor.capture(), any(Wrapper.class));
        assertThat(captor.getAllValues())
                .extracting(GroupChatReplyStep::getStatus)
                .containsExactly(
                        GroupChatConstant.STATUS_FAILED,
                        GroupChatConstant.STATUS_BLOCKED);
    }

    @Test
    void saveGuardRejectsAnyNonTerminalTurnInWorld() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupTurnRecoveryService service = new GroupTurnRecoveryService(mock(com.me.galchat.mapper.GroupTurnCheckpointMapper.class),
                turnMapper, mock(GroupChatReplyStepMapper.class), mock(GroupChatMessageMapper.class));
        when(turnMapper.countNonTerminalByUserWorldId(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.assertNoNonTerminalTurns(1L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("未完成");
    }

    @Test
    void characterDecisionFailureBlocksPendingTail() {
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupTurnRecoveryService service =
                new GroupTurnRecoveryService(mock(com.me.galchat.mapper.GroupTurnCheckpointMapper.class),
                        mock(GroupChatTurnMapper.class),
                        stepMapper,
                        mock(GroupChatMessageMapper.class));

        service.blockPendingSteps(10L, "行动输出格式错误");

        var captor = org.mockito.ArgumentCaptor.forClass(
                GroupChatReplyStep.class);
        verify(stepMapper).update(
                captor.capture(), any(Wrapper.class));
        assertThat(captor.getValue())
                .extracting(
                        GroupChatReplyStep::getStatus,
                        GroupChatReplyStep::getErrorMessage)
                .containsExactly(
                        GroupChatConstant.STATUS_BLOCKED,
                        "行动输出格式错误");
    }
}
