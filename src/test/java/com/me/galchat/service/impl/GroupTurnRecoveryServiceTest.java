package com.me.galchat.service.impl;

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

    @Test
    void recoveryFailsRunningDataAndCancelsPendingSteps() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper = mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupTurnRecoveryService service = new GroupTurnRecoveryService(
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

    @Test
    void saveGuardRejectsAnyNonTerminalTurnInWorld() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupTurnRecoveryService service = new GroupTurnRecoveryService(
                turnMapper, mock(GroupChatReplyStepMapper.class), mock(GroupChatMessageMapper.class));
        when(turnMapper.countNonTerminalByUserWorldId(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.assertNoNonTerminalTurns(1L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("未完成");
    }
}
