package com.me.galchat.service.impl;

import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.DiceRollSummaryVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.tool.GroupToolCallStore;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.service.ICocDiceOrchestrationService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgUnconsciousRecoveryServiceTest {

    @Test
    void stunnedCharacterSkipsActiveSlotAndConsumesOneRound() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        ICocDiceOrchestrationService dice =
                mock(ICocDiceOrchestrationService.class);
        TrpgCombatLifecycleService combatLifecycle =
                mock(TrpgCombatLifecycleService.class);
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        characterMapper, dice,
                        mock(GroupChatReplyStepMapper.class),
                        mock(GroupChatTurnMapper.class),
                        mock(GroupChatMessageMapper.class),
                        mock(DiceRollSummaryMapper.class),
                        mock(GroupConversationService.class),
                        new DiceRollMessageCodec(
                                JsonMapper.builder().build()),
                        mock(GroupToolCallStore.class),
                        mock(GroupTurnCheckpointService.class),
                        combatLifecycle);
        GroupConversation conversation = new GroupConversation().setId(7L);
        GroupChatTurn turn = combatTurn();
        GroupChatReplyStep attack = combatSlot().getFirst();
        CocCharacter card = new CocCharacter()
                .setId(71L).setRunId(7L).setName("林恩")
                .setStunnedRemainingRounds(3)
                .setUnconscious(false).setDying(false).setDead(false);
        when(characterMapper.selectById(71L)).thenReturn(card);
        when(characterMapper.updateById(card)).thenReturn(1);

        var execution = service.handle(conversation, turn, attack);

        assertThat(execution.outcome())
                .isEqualTo(TrpgUnconsciousRecoveryService.Outcome.SKIPPED);
        assertThat(card.getStunnedRemainingRounds()).isEqualTo(2);
        verify(characterMapper).updateById(card);
        verify(combatLifecycle).forfeitCurrentRoundSlot(7L, 71L);
        verify(dice, never()).requestUnconsciousRecovery(any(), any(), any());
    }

    @Test
    void pendingCoverForfeitSkipsTheNextActiveSlotAndIsConsumed() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        ICocDiceOrchestrationService dice =
                mock(ICocDiceOrchestrationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgCombatLifecycleService combatLifecycle =
                mock(TrpgCombatLifecycleService.class);
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        characterMapper, dice, stepMapper,
                        mock(GroupChatTurnMapper.class),
                        mock(GroupChatMessageMapper.class),
                        mock(DiceRollSummaryMapper.class),
                        mock(GroupConversationService.class),
                        new DiceRollMessageCodec(
                                JsonMapper.builder().build()),
                        mock(GroupToolCallStore.class),
                        mock(GroupTurnCheckpointService.class),
                        combatLifecycle);
        GroupConversation conversation = new GroupConversation().setId(7L);
        GroupChatTurn turn = combatTurn();
        GroupChatReplyStep attack = combatSlot().getFirst();
        CocCharacter card = new CocCharacter()
                .setId(71L).setRunId(7L).setName("林恩")
                .setCoverActionForfeitPending(true)
                .setUnconscious(false).setDying(false).setDead(false);
        when(characterMapper.selectById(71L)).thenReturn(card);
        when(characterMapper.updateById(card)).thenReturn(1);

        var execution = service.handle(conversation, turn, attack);

        assertThat(execution.outcome())
                .isEqualTo(TrpgUnconsciousRecoveryService.Outcome.SKIPPED);
        assertThat(card.getCoverActionForfeitPending()).isFalse();
        verify(characterMapper).updateById(card);
        verify(combatLifecycle).forfeitCurrentRoundSlot(7L, 71L);
        verify(dice, never()).requestUnconsciousRecovery(any(), any(), any());
    }

    @Test
    void playerRecoveryCreatesDiceAndPausesAtWaitingDiceBoundary() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        ICocDiceOrchestrationService dice =
                mock(ICocDiceOrchestrationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupToolCallStore toolCalls = mock(GroupToolCallStore.class);
        GroupTurnCheckpointService checkpoints =
                mock(GroupTurnCheckpointService.class);
        DiceRollMessageCodec codec = new DiceRollMessageCodec(
                JsonMapper.builder().build());
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        characterMapper, dice, stepMapper, turnMapper,
                        messageMapper, mock(DiceRollSummaryMapper.class),
                        conversations, codec, toolCalls, checkpoints,
                        mock(TrpgCombatLifecycleService.class));
        GroupConversation conversation = new GroupConversation().setId(7L);
        GroupChatTurn turn = combatTurn();
        List<GroupChatReplyStep> steps = combatSlot();
        GroupChatReplyStep attack = steps.getFirst();
        CocCharacter card = new CocCharacter()
                .setId(71L).setRunId(7L).setName("林恩")
                .setActorType("PLAYER").setCon(55)
                .setUnconscious(true).setDying(false).setDead(false);
        when(characterMapper.selectById(71L)).thenReturn(card);
        when(stepMapper.selectList(any())).thenReturn(steps);
        when(conversations.nextSequence(7L)).thenReturn(20L);
        when(messageMapper.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0).setId(90L);
                    return 1;
                });
        KpDiceToolResult roll = roll(DiceRollConstant.STATUS_PENDING);
        when(dice.requestUnconsciousRecovery(7L, 7L, 71L))
                .thenReturn(roll);

        var execution = service.handle(conversation, turn, attack);

        assertThat(execution.outcome())
                .isEqualTo(TrpgUnconsciousRecoveryService.Outcome.PAUSED);
        assertThat(execution.events())
                .extracting(event -> event.getEventType())
                .containsExactly(GroupChatConstant.EVENT_DICE_ROLL_CREATED);
        assertThat(execution.events().getFirst().getToolName())
                .isEqualTo("systemUnconsciousRecoveryCon");
        assertThat(attack.getActionType()).isEqualTo(
                GroupChatConstant.ACTION_COMBAT_UNCONSCIOUS_RECOVERY);
        assertThat(attack.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(turn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(steps.subList(1, 4))
                .extracting(GroupChatReplyStep::getStatus)
                .containsOnly(GroupChatConstant.STATUS_CANCELLED);
        assertThat(steps.get(4).getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        verify(toolCalls).saveSystemDice(attack.getId(), roll);
        verify(checkpoints).recordBoundary(
                turn, attack, GroupTurnCheckpointService.WAITING_DICE);
    }

    @Test
    void completedRecoveryStepFinishesWithoutRollingAgain() {
        ICocDiceOrchestrationService dice =
                mock(ICocDiceOrchestrationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        DiceRollSummaryMapper summaries = mock(DiceRollSummaryMapper.class);
        GroupTurnCheckpointService checkpoints =
                mock(GroupTurnCheckpointService.class);
        DiceRollMessageCodec codec = new DiceRollMessageCodec(
                JsonMapper.builder().build());
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        mock(CocCharacterMapper.class), dice,
                        stepMapper, turnMapper, messageMapper, summaries,
                        mock(GroupConversationService.class), codec,
                        mock(GroupToolCallStore.class), checkpoints,
                        mock(TrpgCombatLifecycleService.class));
        GroupChatTurn turn = combatTurn();
        GroupChatReplyStep recovery = combatSlot().getFirst()
                .setActionType(
                        GroupChatConstant.ACTION_COMBAT_UNCONSCIOUS_RECOVERY)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                new GroupChatMessage()
                        .setReplyStepId(recovery.getId())
                        .setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                        .setContent(codec.encode(101L, List.of(1)))));
        when(summaries.selectById(101L)).thenReturn(
                new DiceRollSummary().setId(101L)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));

        var execution = service.handle(
                new GroupConversation().setId(7L), turn, recovery);

        assertThat(execution.outcome())
                .isEqualTo(TrpgUnconsciousRecoveryService.Outcome.COMPLETED);
        assertThat(recovery.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        verify(stepMapper).updateById(recovery);
        verify(checkpoints).recordBoundary(
                turn, recovery, GroupTurnCheckpointService.COMPLETED);
        verify(dice, never()).requestUnconsciousRecovery(any(), any(), any());
    }

    @Test
    void sceneRecoveryCreatesDiceWithoutCancellingLaterInvestigators() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        ICocDiceOrchestrationService dice =
                mock(ICocDiceOrchestrationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        DiceRollMessageCodec codec = new DiceRollMessageCodec(
                JsonMapper.builder().build());
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        characterMapper, dice, stepMapper, turnMapper,
                        messageMapper, mock(DiceRollSummaryMapper.class),
                        conversations, codec,
                        mock(GroupToolCallStore.class),
                        mock(GroupTurnCheckpointService.class),
                        mock(TrpgCombatLifecycleService.class));
        GroupConversation conversation = new GroupConversation().setId(7L);
        GroupChatTurn turn = sceneTurn();
        GroupChatReplyStep current = sceneStep(51L, 1, 71L);
        GroupChatReplyStep later = sceneStep(52L, 2, 72L);
        CocCharacter card = new CocCharacter()
                .setId(71L).setRunId(7L).setName("林恩")
                .setActorType("PLAYER").setCon(55)
                .setUnconscious(true).setDying(false).setDead(false);
        when(characterMapper.selectById(71L)).thenReturn(card);
        when(stepMapper.selectList(any()))
                .thenReturn(List.of(current, later));
        when(conversations.nextSequence(7L)).thenReturn(20L);
        when(messageMapper.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0).setId(90L);
                    return 1;
                });
        when(dice.requestUnconsciousRecovery(7L, 7L, 71L))
                .thenReturn(roll(DiceRollConstant.STATUS_PENDING));

        var execution = service.handle(conversation, turn, current);

        assertThat(execution.outcome())
                .isEqualTo(TrpgUnconsciousRecoveryService.Outcome.PAUSED);
        assertThat(current.getActionType())
                .isEqualTo(
                        GroupChatConstant.ACTION_TRPG_UNCONSCIOUS_RECOVERY);
        assertThat(current.getGroupName()).isEqualTo("旧宅探索");
        assertThat(current.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(turn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(later.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
    }

    @Test
    void successfulSceneRecoveryRestoresOriginalActionForSameTurn() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        DiceRollSummaryMapper summaries = mock(DiceRollSummaryMapper.class);
        DiceRollMessageCodec codec = new DiceRollMessageCodec(
                JsonMapper.builder().build());
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        characterMapper,
                        mock(ICocDiceOrchestrationService.class),
                        stepMapper, mock(GroupChatTurnMapper.class),
                        messageMapper, summaries,
                        mock(GroupConversationService.class), codec,
                        mock(GroupToolCallStore.class),
                        mock(GroupTurnCheckpointService.class),
                        mock(TrpgCombatLifecycleService.class));
        GroupChatTurn turn = sceneTurn();
        GroupChatReplyStep recovery = sceneStep(51L, 1, 71L)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_UNCONSCIOUS_RECOVERY);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                new GroupChatMessage()
                        .setReplyStepId(recovery.getId())
                        .setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                        .setContent(codec.encode(101L, List.of(1)))));
        when(summaries.selectById(101L)).thenReturn(
                new DiceRollSummary().setId(101L)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));
        when(characterMapper.selectById(71L)).thenReturn(
                new CocCharacter().setId(71L)
                        .setUnconscious(false).setDying(false).setDead(false));

        var execution = service.handle(
                new GroupConversation().setId(7L), turn, recovery);

        assertThat(execution.outcome().name()).isEqualTo("PROCEED");
        assertThat(recovery.getActionType())
                .isEqualTo(GroupChatConstant.ACTION_TRPG_SCENE);
        assertThat(recovery.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        assertThat(recovery.getGroupName()).isEqualTo("旧宅探索");
        verify(stepMapper).updateById(recovery);
    }

    @Test
    void failedSceneRecoveryCompletesRecoveryAndSkipsOriginalAction() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        DiceRollSummaryMapper summaries = mock(DiceRollSummaryMapper.class);
        GroupTurnCheckpointService checkpoints =
                mock(GroupTurnCheckpointService.class);
        DiceRollMessageCodec codec = new DiceRollMessageCodec(
                JsonMapper.builder().build());
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        characterMapper,
                        mock(ICocDiceOrchestrationService.class),
                        stepMapper, mock(GroupChatTurnMapper.class),
                        messageMapper, summaries,
                        mock(GroupConversationService.class), codec,
                        mock(GroupToolCallStore.class), checkpoints,
                        mock(TrpgCombatLifecycleService.class));
        GroupChatTurn turn = sceneTurn();
        GroupChatReplyStep recovery = sceneStep(51L, 1, 71L)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_UNCONSCIOUS_RECOVERY);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                new GroupChatMessage()
                        .setReplyStepId(recovery.getId())
                        .setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                        .setContent(codec.encode(101L, List.of(1)))));
        when(summaries.selectById(101L)).thenReturn(
                new DiceRollSummary().setId(101L)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));
        when(characterMapper.selectById(71L)).thenReturn(
                new CocCharacter().setId(71L)
                        .setUnconscious(true).setDying(false).setDead(false));

        var execution = service.handle(
                new GroupConversation().setId(7L), turn, recovery);

        assertThat(execution.outcome())
                .isEqualTo(TrpgUnconsciousRecoveryService.Outcome.COMPLETED);
        assertThat(recovery.getActionType())
                .isEqualTo(
                        GroupChatConstant.ACTION_TRPG_UNCONSCIOUS_RECOVERY);
        assertThat(recovery.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        verify(checkpoints).recordBoundary(
                turn, recovery, GroupTurnCheckpointService.COMPLETED);
    }

    @Test
    void dyingSceneInvestigatorSkipsWithoutRecoveryRoll() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        ICocDiceOrchestrationService dice =
                mock(ICocDiceOrchestrationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgUnconsciousRecoveryService service =
                new TrpgUnconsciousRecoveryService(
                        characterMapper, dice, stepMapper,
                        mock(GroupChatTurnMapper.class),
                        mock(GroupChatMessageMapper.class),
                        mock(DiceRollSummaryMapper.class),
                        mock(GroupConversationService.class),
                        new DiceRollMessageCodec(
                                JsonMapper.builder().build()),
                        mock(GroupToolCallStore.class),
                        mock(GroupTurnCheckpointService.class),
                        mock(TrpgCombatLifecycleService.class));
        GroupChatReplyStep scene = sceneStep(51L, 1, 71L);
        when(characterMapper.selectById(71L)).thenReturn(
                new CocCharacter().setId(71L)
                        .setUnconscious(true).setDying(true).setDead(false));

        var execution = service.handle(
                new GroupConversation().setId(7L), sceneTurn(), scene);

        assertThat(execution.outcome())
                .isEqualTo(TrpgUnconsciousRecoveryService.Outcome.SKIPPED);
        assertThat(scene.getActionType())
                .isEqualTo(
                        GroupChatConstant.ACTION_TRPG_UNCONSCIOUS_RECOVERY);
        assertThat(scene.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        verify(stepMapper).updateById(scene);
        verify(dice, never()).requestUnconsciousRecovery(any(), any(), any());
    }

    private GroupChatTurn combatTurn() {
        return new GroupChatTurn().setId(30L).setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
    }

    private GroupChatTurn sceneTurn() {
        return new GroupChatTurn().setId(30L).setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
    }

    private GroupChatReplyStep sceneStep(
            Long id, int order, Long cardId) {
        return step(id, order, GroupChatConstant.ACTION_TRPG_SCENE, cardId)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                .setGroupName("旧宅探索");
    }

    private List<GroupChatReplyStep> combatSlot() {
        return List.of(
                step(41L, 1, GroupChatConstant.ACTION_COMBAT_ATTACK, 71L),
                step(42L, 2,
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE, 71L),
                step(43L, 3,
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 71L)
                        .setItemOrder(2).setParentStepId(42L)
                        .setRootStepId(42L),
                step(44L, 4, GroupChatConstant.ACTION_COMBAT_DEFENSE, null)
                        .setItemOrder(2).setParentStepId(42L)
                        .setRootStepId(42L),
                step(45L, 5,
                        GroupChatConstant.ACTION_COMBAT_ATTACK, 72L)
                        .setItemOrder(3));
    }

    private GroupChatReplyStep step(
            Long id, int order, String actionType, Long cardId) {
        return new GroupChatReplyStep().setId(id).setTurnId(30L)
                .setStepNo(order).setItemOrder(order)
                .setActionType(actionType)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSubjectCharacterId(cardId)
                .setStatus(GroupChatConstant.STATUS_PENDING);
    }

    private KpDiceToolResult roll(String status) {
        return new KpDiceToolResult(
                new DiceRollSummaryVO().setId(101L)
                        .setConversationId(7L)
                        .setRoundCount(1).setStatus(status),
                List.of(), null);
    }
}
