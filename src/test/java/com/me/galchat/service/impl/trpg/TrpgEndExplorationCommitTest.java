package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupEndExplorationDTO;
import com.me.galchat.domain.po.*;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ITrpgSaveService;
import com.me.galchat.service.impl.group.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrpgEndExplorationCommitTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "insert-failure", "cancel-failure", "commit-failure", "redis-failure"})
    void publishesSceneProgressOnlyAfterTheActionCommits(String outcome) {
        var events = new ArrayList<String>();
        var transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { events.add("begin"); }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                if (outcome.equals("commit-failure")) throw new IllegalStateException("commit failed");
                events.add("commit");
            }
            @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
        });
        var conversations = mock(GroupConversationService.class);
        var locks = mock(GroupConversationLockService.class);
        var turns = mock(GroupChatTurnMapper.class);
        var steps = mock(GroupChatReplyStepMapper.class);
        var messages = mock(GroupChatMessageMapper.class);
        var plans = mock(GroupReplyPlanMapper.class);
        var items = mock(GroupReplyPlanItemMapper.class);
        var recovery = mock(GroupTurnRecoveryService.class);
        var progress = mock(TrpgSceneProgressStore.class);
        var chat = mock(GroupChatService.class);
        var lifecycle = new TrpgSceneLifecycleService(conversations, steps, turns, plans, items, recovery,
                progress, mock(TrpgSceneSummaryService.class), mock(GroupReplyPlanService.class),
                mock(TrpgChildScenePlanService.class), mock(TrpgTemporaryInsanityService.class));
        var service = new TrpgTurnExecutionService(conversations, locks, mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class), turns, steps, messages, recovery, chat, transactions,
                mock(TrpgSceneSelectionService.class), lifecycle, mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class), mock(GroupAgentDecisionStore.class), mock(DiceRollSummaryMapper.class),
                mock(DiceRollMessageCodec.class), mock(TrpgCombatLifecycleService.class), plans,
                mock(GroupTurnCheckpointService.class), mock(TrpgUnconsciousRecoveryService.class), mock(ITrpgSaveService.class));
        var conversation = new GroupConversation().setId(7L).setMode("trpg").setStatus("active").setActiveReplyPlanId(21L);
        var turn = new GroupChatTurn().setId(101L).setConversationId(7L).setStatus("waiting_input")
                .setPlanSource("SCENE").setPlanId(21L).setPlanContextId(301L);
        var step = new GroupChatReplyStep().setId(102L).setTurnId(101L).setStepNo(1).setSpeakerType("user")
                .setSpeakerId(501L).setSubjectCharacterId(601L).setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setStatus("waiting_input");
        var kp = new GroupChatReplyStep().setId(103L).setTurnId(101L).setStepNo(2).setSpeakerType("kp")
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE).setStatus("pending");
        var lock = new GroupConversationLockService.OwnedLock(mock(RLock.class), -1);
        when(conversations.requireAuthorized(7L)).thenReturn(conversation);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(locks.tryLockWithOwner(7L)).thenReturn(lock);
        when(turns.selectById(101L)).thenReturn(turn);
        when(steps.selectById(102L)).thenReturn(step);
        when(steps.selectById(103L)).thenReturn(kp);
        when(steps.selectList(any())).thenReturn(List.of(kp));
        when(plans.selectById(21L)).thenReturn(new GroupReplyPlan().setId(21L).setSource("SCENE").setContextId(301L));
        when(items.selectList(any())).thenReturn(List.of(new GroupReplyPlanItem().setSubjectCharacterId(601L)));
        // Faithful store: a write is visible to subsequent reads.
        var ready = new java.util.HashSet<String>();
        when(progress.readyActors(7L, 21L)).thenAnswer(call -> Set.copyOf(ready));
        doAnswer(call -> {
            events.add("redis-ready");
            if (outcome.equals("redis-failure")) throw new IllegalStateException("redis failed");
            ready.add("character-card:601");
            return null;
        }).when(progress).markReady(7L, 21L, 601L);
        doAnswer(call -> { events.add("redis-finish"); return null; }).when(progress).requestFinish(7L, 21L);
        doAnswer(call -> {
            events.add("cancel-pending");
            if (outcome.equals("cancel-failure")) throw new IllegalStateException("cancel failed");
            return null;
        })
                .when(recovery).cancelPendingInvestigatorSteps(eq(101L), anyString());
        when(messages.insert(any(GroupChatMessage.class))).thenAnswer(call -> {
            events.add("insert");
            if (outcome.equals("insert-failure")) throw new IllegalStateException("insert failed");
            call.<GroupChatMessage>getArgument(0).setId(401L);
            return 1;
        });
        when(chat.streamPersistedStep(conversation, turn, kp)).thenAnswer(call -> {
            events.add("generate");
            return reactor.core.publisher.Flux.empty();
        });
        var request = new GroupEndExplorationDTO();
        request.setClientRequestId("end-1");
        if (outcome.equals("success")) {
            service.endExploration(7L, 101L, 102L, request).collectList().block();
            assertThat(events).containsSubsequence("insert", "cancel-pending", "commit", "redis-ready", "redis-finish", "generate");
            assertThat(step.getStatus()).isEqualTo("completed");
        } else {
            assertThatThrownBy(() -> service.endExploration(7L, 101L, 102L, request).collectList().block())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(outcome.replace("-failure", " failed"));
            if (outcome.equals("redis-failure")) {
                assertThat(events).containsSubsequence("insert", "commit", "redis-ready");
                assertThat(events).doesNotContain("rollback", "generate");
            } else {
                assertThat(events).doesNotContain("redis-ready", "redis-finish", "generate");
            }
        }
        verify(locks).unlock(lock);
    }
}
