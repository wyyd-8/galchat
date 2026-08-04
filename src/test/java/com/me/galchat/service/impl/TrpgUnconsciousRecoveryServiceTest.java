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
        assertThat(attack.getActionType()).isEqualTo(
                GroupChatConstant.ACTION_COMBAT_UNCONSCIOUS_RECOVERY);
        assertThat(attack.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(turn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(steps.subList(1, 4))
                .extracting(GroupChatReplyStep::getStatus)
                .containsOnly(GroupChatConstant.STATUS_CANCELLED);
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

    private GroupChatTurn combatTurn() {
        return new GroupChatTurn().setId(30L).setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
    }

    private List<GroupChatReplyStep> combatSlot() {
        return List.of(
                step(41L, 1, GroupChatConstant.ACTION_COMBAT_ATTACK, 71L),
                step(42L, 2,
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 71L),
                step(43L, 3, GroupChatConstant.ACTION_COMBAT_DEFENSE, null),
                step(44L, 4,
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE, 71L));
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
