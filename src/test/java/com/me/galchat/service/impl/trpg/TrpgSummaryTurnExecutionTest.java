package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupTurnContinueDTO;
import com.me.galchat.domain.po.*;
import com.me.galchat.groupchat.runtime.*;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ITrpgSaveService;
import com.me.galchat.service.impl.group.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.support.*;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrpgSummaryTurnExecutionTest {
    final GroupConversationService conversations = mock(GroupConversationService.class);
    final GroupConversationLockService locks = mock(GroupConversationLockService.class);
    final GroupTurnPlanResolver resolver = mock(GroupTurnPlanResolver.class);
    final GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
    final GroupChatReplyStepMapper steps = mock(GroupChatReplyStepMapper.class);
    final GroupChatMessageMapper messages = mock(GroupChatMessageMapper.class);
    final GroupTurnCheckpointService checkpoint = mock(GroupTurnCheckpointService.class);
    final GroupTurnRecoveryService recovery = mock(GroupTurnRecoveryService.class);
    final GroupChatService chat = mock(GroupChatService.class);
    final ITrpgSaveService saves = mock(ITrpgSaveService.class);
    final TrpgCompletionService completion = mock(TrpgCompletionService.class);
    final TrpgSceneLifecycleService scenes = mock(TrpgSceneLifecycleService.class);
    final GroupRuntimeRegistry runtimes = mock(GroupRuntimeRegistry.class);
    final GroupConversation conversation = new GroupConversation().setId(7L).setMode("trpg").setStatus("active");
    GroupChatTurn turn;
    GroupChatReplyStep step;
    TrpgTurnExecutionService service;

    @BeforeEach
    void setup() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(GroupChatReplyStep.class, GroupChatTurn.class);
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(new SimpleTransactionStatus()));
        doAnswer(call -> { call.<Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                .accept(new SimpleTransactionStatus()); return null; }).when(transactions).executeWithoutResult(any());
        service = new TrpgTurnExecutionService(conversations, locks, resolver, runtimes, turns, steps, messages,
                recovery, chat, transactions, mock(TrpgSceneSelectionService.class), scenes,
                mock(TrpgSceneSelectionStore.class), mock(TrpgParticipantService.class), mock(GroupAgentDecisionStore.class),
                mock(DiceRollSummaryMapper.class), mock(com.me.galchat.groupchat.dice.DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class), mock(GroupReplyPlanMapper.class), checkpoint,
                mock(TrpgUnconsciousRecoveryService.class), saves);
        service.setCompletionService(completion);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(locks.tryLock(7L)).thenReturn(new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(turns.selectList(any())).thenAnswer(call -> turn == null ? List.of() : List.of(turn));
        when(turns.selectById(any())).thenAnswer(call -> turn);
        when(steps.selectList(any())).thenAnswer(call -> step == null ? List.of() : List.of(step));
        when(steps.selectById(any())).thenAnswer(call -> step);
        when(turns.insert(any(GroupChatTurn.class))).thenAnswer(call -> { turn = call.getArgument(0); turn.setId(9L); return 1; });
        when(steps.insert(any(GroupChatReplyStep.class))).thenAnswer(call -> { step = call.getArgument(0); step.setId(10L); return 1; });
        when(runtimes.require("trpg")).thenReturn(mock(GroupModeRuntime.class));
        when(resolver.resolve(eq(conversation), any())).thenReturn(new GroupTurnPlanResolver.ResolvedTurnPlan(
                GroupChatConstant.TURN_SOURCE_SUMMARY, null, List.of(new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SUMMARY, "kp", null, "summary", "生成总结", 1, 1))));
    }

    GroupTurnContinueDTO request(String id) {
        var result = new GroupTurnContinueDTO(); result.setClientRequestId(id); return result;
    }

    @Test
    void summaryUsesTurnSaveAndSameStepRetryWithoutChatGeneration() {
        doThrow(new IllegalStateException("summary failed")).doAnswer(call -> {
            turn.setStatus("completed"); step.setStatus("completed"); return null;
        }).when(completion).executeUnderLock(eq(conversation), any(), any());
        assertThatThrownBy(() -> service.continueTurn(7L, request("first")).collectList().block())
                .hasMessage("summary failed");
        assertThat(turn.getStatus()).isEqualTo("failed");
        assertThat(step.getStatus()).isEqualTo("failed");
        assertThat(step.getErrorMessage()).isEqualTo("summary failed");
        service.retry(7L, 9L, 10L).collectList().block();
        assertThat(turn.getStatus()).isEqualTo("completed");
        verify(saves).saveBeforeTurn(conversation);
        verify(turns).insert(any(GroupChatTurn.class));
        verify(completion, times(2)).executeUnderLock(conversation, turn, step);
        verifyNoInteractions(chat);
        verify(checkpoint, never()).restore(any(), any());
        verify(resolver, never()).onTurnCompleted(any(), any(GroupChatTurn.class));
    }

    @Test
    void finalSceneFailureRetriesClosingStepAndWaitsBeforeStartingSummary() {
        conversation.setActiveReplyPlanId(20L);
        turn = new GroupChatTurn().setId(9L).setConversationId(7L).setStatus("running").setPlanSource("POST_COMBAT");
        step = new GroupChatReplyStep().setId(10L).setTurnId(9L).setStepNo(2).setSpeakerType("kp")
                .setActionType(GroupChatConstant.ACTION_TRPG_RUN_SCENE_CLOSE).setStatus("pending");
        when(resolver.hasRunFinishRequest(conversation, 9L)).thenReturn(true);
        when(messages.selectList(any())).thenReturn(List.of(new GroupChatMessage().setSequenceNo(42L)));
        doThrow(new IllegalStateException("scene summary failed")).doNothing().when(scenes).closeRunScene(conversation, 42L);
        doAnswer(call -> { conversation.setActiveReplyPlanId(null); return null; }).when(scenes).finishRunSceneUnderLock(conversation);
        assertThatThrownBy(() -> service.continueTurn(7L, request("close1")).collectList().block())
                .hasMessage("scene summary failed");
        assertThat(turn.getStatus()).isEqualTo("failed");
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(20L);
        service.continueTurn(7L, request("close2")).collectList().block();
        assertThat(turn.getStatus()).isEqualTo("completed");
        assertThat(conversation.getActiveReplyPlanId()).isNull();
        assertThat(conversation.getStatus()).isEqualTo("active");
        verify(scenes, times(2)).closeRunScene(conversation, 42L);
        verifyNoInteractions(completion, chat, saves);
        verify(turns, never()).insert(any(GroupChatTurn.class));
    }
}
