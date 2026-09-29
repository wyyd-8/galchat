package com.me.galchat.service.impl.group;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.context.GroupTopicService;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupChatWithdrawalServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                GroupChatTurn.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void withdrawsOnlyTheExpectedTurnAndClearsItsCheckpoint(boolean staleRequest) {
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupChatReplyStepMapper stepMapper = mock(GroupChatReplyStepMapper.class);
        GroupTurnRecoveryService recoveryService = mock(GroupTurnRecoveryService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GroupTurnCheckpointService checkpoints = mock(GroupTurnCheckpointService.class);
        GroupChatFavorRollbackService favor = mock(GroupChatFavorRollbackService.class);
        GroupChatWithdrawalService service = new GroupChatWithdrawalService(
                conversationService,
                lockService,
                turnMapper,
                messageMapper,
                stepMapper,
                mock(GroupChatToolCallMapper.class),
                recoveryService,
                favor, checkpoints,
                mock(GroupTopicService.class),
                transactionTemplate);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(1L)
                .setMode(GroupChatConstant.MODE_CHAT)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(10L)
                .setTriggerMessageId(99L)
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setRevision(0);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                new GroupChatReplyStep().setId(11L).setTurnId(10L)));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        if (staleRequest) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.withdrawLatestTurn(7L, 9L))
                    .hasMessageContaining("群聊记录已变化");
            org.mockito.Mockito.verifyNoInteractions(favor, checkpoints, stepMapper, messageMapper);
            return;
        }
        service.withdrawLatestTurn(7L, 10L);
        verify(checkpoints).clear(7L);
        verify(favor).rollback(1L, List.of(11L));
        verify(recoveryService).assertConversationHasNoNonTerminalTurns(7L);
        verify(stepMapper).delete(any());
        verify(turnMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void selectsLatestNonWithdrawnTurnAndCountsConsecutivePlaceholders() {
        GroupChatTurn candidate = new GroupChatTurn().setId(10L).setStatus(GroupChatConstant.STATUS_COMPLETED);

        GroupChatWithdrawalService.WithdrawCandidate result =
                GroupChatWithdrawalService.selectWithdrawCandidate(List.of(
                        new GroupChatTurn().setId(30L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        new GroupChatTurn().setId(20L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        candidate));

        assertThat(result.turn()).isSameAs(candidate);
        assertThat(result.consecutiveWithdrawCount()).isEqualTo(2);
    }

    @Test
    void noCandidateRemainsAfterThreeWithdrawnTurns() {
        GroupChatWithdrawalService.WithdrawCandidate result =
                GroupChatWithdrawalService.selectWithdrawCandidate(List.of(
                        new GroupChatTurn().setId(30L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        new GroupChatTurn().setId(20L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        new GroupChatTurn().setId(10L).setStatus(GroupChatConstant.STATUS_WITHDRAWN)));

        assertThat(result.turn()).isNull();
        assertThat(result.consecutiveWithdrawCount()).isEqualTo(3);
    }
}
