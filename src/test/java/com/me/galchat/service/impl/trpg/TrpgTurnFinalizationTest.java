package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.me.galchat.domain.dto.GroupTurnContinueDTO;
import com.me.galchat.domain.po.*;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ITrpgSaveService;
import com.me.galchat.service.impl.group.*;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrpgTurnFinalizationTest {
    final GroupConversationService conversations = mock(GroupConversationService.class);
    final GroupConversationLockService locks = mock(GroupConversationLockService.class);
    final GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
    final GroupChatReplyStepMapper steps = mock(GroupChatReplyStepMapper.class);
    final GroupChatMessageMapper messages = mock(GroupChatMessageMapper.class);
    final GroupReplyPlanMapper plans = mock(GroupReplyPlanMapper.class);
    final GroupReplyPlanService replyPlans = mock(GroupReplyPlanService.class);
    final TrpgSceneProgressStore progress = mock(TrpgSceneProgressStore.class);
    final TrpgSceneSummaryService summaries = mock(TrpgSceneSummaryService.class);
    final TrpgChildScenePlanService childPlans = mock(TrpgChildScenePlanService.class);
    final TrpgChildSceneCommandService children = mock(TrpgChildSceneCommandService.class);
    final TrpgCombatLifecycleService combat = mock(TrpgCombatLifecycleService.class);
    final TrpgSceneSelectionService selection = mock(TrpgSceneSelectionService.class);
    final GroupTurnCheckpointService checkpoint = mock(GroupTurnCheckpointService.class);
    final GroupChatService chat = mock(GroupChatService.class);
    final GroupAgentDecisionStore decisions = mock(GroupAgentDecisionStore.class);
    final GroupConversation conversation = new GroupConversation().setId(7L).setMode("trpg")
            .setStatus("active").setActiveReplyPlanId(20L);
    final GroupChatTurn turn = new GroupChatTurn().setId(9L).setConversationId(7L)
            .setPlanSource("SCENE").setStatus("running");
    final GroupChatReplyStep reply = new GroupChatReplyStep().setId(10L).setTurnId(9L)
            .setStepNo(1).setSpeakerType("character").setActionType("trpg_scene_action")
            .setStatus("completed").setOutputMessageId(40L);
    final List<GroupChatReplyStep> storedSteps = new ArrayList<>(List.of(reply));
    TrpgTurnExecutionService service;

    @BeforeEach
    void setup() {
        MybatisPlusTestSupport.initialize(GroupChatTurn.class, GroupChatReplyStep.class, GroupChatMessage.class);
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                call.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
        doAnswer(call -> {
            call.<Consumer<TransactionStatus>>getArgument(0).accept(new SimpleTransactionStatus());
            return null;
        }).when(transactions).executeWithoutResult(any());
        var recovery = new GroupTurnRecoveryService(turns, steps, messages);
        var scenes = new TrpgSceneLifecycleService(conversations, steps, turns, plans,
                mock(GroupReplyPlanItemMapper.class), recovery, progress, summaries, replyPlans,
                childPlans, mock(TrpgTemporaryInsanityService.class));
        var resolver = new GroupTurnPlanResolver(replyPlans, selection, scenes,
                mock(TrpgRunLifecycleService.class), combat, children, mock(TrpgProposalOrderService.class));
        service = new TrpgTurnExecutionService(conversations, locks, resolver,
                mock(GroupRuntimeRegistry.class), turns, steps, messages, recovery, chat, transactions,
                selection, scenes, mock(TrpgSceneSelectionStore.class), mock(TrpgParticipantService.class),
                decisions, mock(DiceRollSummaryMapper.class), mock(DiceRollMessageCodec.class), combat,
                plans, checkpoint, mock(TrpgUnconsciousRecoveryService.class), mock(ITrpgSaveService.class));
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(conversations.requireAuthorized(7L)).thenReturn(conversation);
        when(locks.tryLockWithOwner(7L)).thenReturn(new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(turns.selectById(9L)).thenReturn(turn);
        when(turns.selectList(any())).thenAnswer(call ->
                matchesStatus(call.getArgument(0), turn.getStatus()) ? List.of(turn) : List.of());
        when(turns.update(any(GroupChatTurn.class), any())).thenAnswer(call -> {
            if (matchesStatus(call.getArgument(1), turn.getStatus())) {
                turn.setStatus(call.<GroupChatTurn>getArgument(0).getStatus());
            }
            return 1;
        });
        when(steps.selectList(any())).thenAnswer(call -> matchingSteps(call.getArgument(0)));
        when(steps.selectCount(any())).thenAnswer(call -> (long) matchingSteps(call.getArgument(0)).size());
        when(steps.selectById(any())).thenAnswer(call -> storedSteps.stream()
                .filter(step -> step.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(steps.insert(any(GroupChatReplyStep.class))).thenAnswer(call -> {
            GroupChatReplyStep step = call.getArgument(0);
            step.setId(10L + storedSteps.size());
            storedSteps.add(step);
            return 1;
        });
        when(steps.update(any(GroupChatReplyStep.class), any())).thenAnswer(call -> {
            GroupChatReplyStep update = call.getArgument(0);
            matchingSteps(call.getArgument(1)).forEach(step -> step.setStatus(update.getStatus())
                    .setErrorMessage(update.getErrorMessage()));
            return 1;
        });
        when(plans.selectById(20L)).thenReturn(new GroupReplyPlan().setId(20L).setSource("SCENE").setContextId(30L));
        when(progress.isFinishRequested(7L, 20L)).thenReturn(true);
    }

    // Regression: once all replies are complete, a failed transition must still have a retry target.
    // Keep the real dispatcher and scene lifecycle; fail only the selected transition dependency.
    @ParameterizedTest
    @CsvSource({"main,continue", "main,retry", "child,continue", "child,retry",
            "start_child,continue", "start_child,retry", "start_combat,continue", "start_combat,retry",
            "post_combat,continue", "post_combat,retry", "selection,continue", "selection,retry"})
    void retriesOnlyFinalizationAfterTransitionFailure(String branch, String entry) {
        var failure = new IllegalStateException("transition failed");
        switch (branch) {
            case "main", "child" -> {
                if (branch.equals("child")) {
                    when(plans.selectById(20L)).thenReturn(new GroupReplyPlan().setId(20L)
                            .setSource("SCENE").setContextId(30L).setParentPlanId(19L));
                }
                when(summaries.summarize(7L, 30L, 20L)).thenThrow(failure).thenReturn(null);
            }
            case "start_child" -> when(children.finalizeStartAfterTurn(conversation, turn))
                    .thenThrow(failure).thenReturn(true);
            case "start_combat" -> when(combat.finalizeStartAfterTurn(conversation, turn))
                    .thenThrow(failure).thenReturn(true);
            case "post_combat" -> {
                turn.setPlanSource("POST_COMBAT");
                when(replyPlans.finishActiveUnderLock(conversation)).thenThrow(failure).thenReturn(null);
            }
            case "selection" -> {
                turn.setPlanSource("SCENE_SELECTION");
                when(selection.finalizeSelections(conversation)).thenThrow(failure).thenReturn(true);
            }
            default -> throw new AssertionError(branch);
        }

        assertThatThrownBy(() -> service.continueTurn(7L, new GroupTurnContinueDTO()).collectList().block())
                .hasMessage("transition failed");
        assertThat(turn.getStatus()).isEqualTo("failed");
        assertThat(storedSteps).hasSize(2);
        GroupChatReplyStep closing = storedSteps.getLast();
        assertThat(closing.getStatus()).isEqualTo("failed");
        assertThat(closing.getErrorMessage()).isEqualTo("transition failed");
        assertThat(closing.getStepNo()).isEqualTo(2);

        var events = (entry.equals("retry") ? service.retry(7L, 9L, closing.getId())
                : service.continueTurn(7L, new GroupTurnContinueDTO())).collectList().block();

        assertThat(events).extracting(event -> event.getEventType()).contains("turn.completed");
        assertThat(turn.getStatus()).isEqualTo("completed");
        assertThat(storedSteps).hasSize(2);
        assertThat(closing.getStatus()).isEqualTo("completed");
        assertThat(closing.getErrorMessage()).isNull();
        assertThat(reply.getStatus()).isEqualTo("completed");
        assertThat(reply.getOutputMessageId()).isEqualTo(40L);
        verifyNoInteractions(chat, decisions);
        verify(checkpoint, never()).restore(any(), any());
        verify(messages, never()).delete(any());
        if (branch.equals("child")) verify(childPlans).finishChildUnderLock(eq(conversation), any());
        if (branch.equals("main")) verify(replyPlans).finishActiveUnderLock(conversation);
    }

    @Test
    void interruptedFinalizationResumesWithoutAnActionCheckpoint() {
        storedSteps.add(new GroupChatReplyStep().setId(11L).setTurnId(9L).setStepNo(2)
                .setActionType("trpg_turn_finalize").setSpeakerType("kp").setStatus("running"));

        service.continueTurn(7L, new GroupTurnContinueDTO()).collectList().block();

        assertThat(turn.getStatus()).isEqualTo("completed");
        assertThat(storedSteps).hasSize(2).allMatch(step -> step.getStatus().equals("completed"));
        verify(summaries).summarize(7L, 30L, 20L);
        verifyNoInteractions(chat, decisions);
        verify(checkpoint, never()).restore(any(), any());
    }

    private List<GroupChatReplyStep> matchingSteps(AbstractWrapper<?, ?, ?> query) {
        String sql = query.getSqlSegment();
        var values = query.getParamNameValuePairs().values();
        return storedSteps.stream().filter(step -> matchesStatus(query, step.getStatus()))
                .filter(step -> !sql.contains("action_type") || values.contains(step.getActionType()))
                .toList();
    }

    private boolean matchesStatus(AbstractWrapper<?, ?, ?> query, String status) {
        if (!query.getSqlSegment().contains("status")) return true;
        var values = query.getParamNameValuePairs().values();
        Set<String> statuses = Set.of("pending", "running", "completed", "failed", "blocked", "cancelled",
                "paused", "waiting_input", "waiting_dice", "waiting_interaction");
        return values.stream().filter(statuses::contains).anyMatch(status::equals);
    }
}
