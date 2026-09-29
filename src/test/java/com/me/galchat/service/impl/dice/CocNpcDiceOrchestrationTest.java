package com.me.galchat.service.impl.dice;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.HealingMode;
import com.me.galchat.constant.HealingSourceMode;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.service.DiceFollowUpLocator;
import com.me.galchat.service.DiceMessageRoundAppender;
import com.me.galchat.service.DiceRandomSource;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.trpg.TrpgCombatLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CocNpcDiceOrchestrationTest {
    private CocDiceOrchestrationService service;
    private CocCharacter npc;

    @BeforeEach
    void setUp() {
        var summaryMapper = mock(DiceRollSummaryMapper.class);
        var resultMapper = mock(DiceRollResultMapper.class);
        List<DiceRollResult> stored = new ArrayList<>();
        when(summaryMapper.insert(any(DiceRollSummary.class))).thenAnswer(call -> {
            call.<DiceRollSummary>getArgument(0).setId(101L);
            return 1;
        });
        when(summaryMapper.updateById(any(DiceRollSummary.class))).thenReturn(1);
        when(resultMapper.insert(any(DiceRollResult.class))).thenAnswer(call -> {
            DiceRollResult row = call.getArgument(0);
            row.setId(201L + stored.size());
            stored.add(row);
            return 1;
        });
        when(resultMapper.selectList(any())).thenReturn(stored);
        when(resultMapper.updateById(any(DiceRollResult.class))).thenReturn(1);

        var cards = mock(ICharacterCardService.class);
        npc = new CocCharacter().setId(99L).setRunId(7L)
                .setActorType("NPC").setParticipantId(null).setName("守卫")
                .setHpCurrent(8).setHpMax(10).setCon(60)
                .setDead(false).setMajorWound(false).setUnconscious(false);
        when(cards.requireDiceCharacter(7L, "守卫")).thenReturn(
                new CocDiceCharacterVO(99L, "NPC", null, "守卫",
                        Map.of("侦查", 60), 8, 10, 50, 50, 60, 0,
                        false, false, false, false, false, null, null));
        when(cards.lockDiceCharacter(7L, 99L)).thenReturn(npc);
        var conversations = mock(GroupConversationService.class);
        when(conversations.requireActive(7L)).thenReturn(
                new GroupConversation().setId(7L).setStatus("active"));
        service = new CocDiceOrchestrationService(
                new DiceRollInternalServiceImpl(summaryMapper, resultMapper),
                cards, conversations, mock(DiceFollowUpLocator.class),
                new CocDiceSummaryFormatter(), mock(DiceRandomSource.class),
                mock(DiceMessageRoundAppender.class), mock(TrpgCombatLifecycleService.class));
    }

    @Test
    void npcCheckCompletesWithoutWaitingForPlayerDice() {
        var result = service.requestCheck(7L, 7L, new KpDiceRequestDTOs.Check(
                "守卫检查现场", CocCheckDifficulty.REGULAR,
                new KpDiceRequestDTOs.CheckTarget("守卫", "侦查", null)));

        assertAutomaticallyResolved(result);
        assertThat(result.results().getFirst().getResultData().getResult()).isBetween(1, 100);
    }

    @Test
    void npcDamageAutomaticallyRollsAndAppliesHpLoss() {
        var result = service.rollDamage(7L, 7L, new KpDiceRequestDTOs.Damage(
                "守卫跌倒", List.of(new KpDiceRequestDTOs.DamageTarget("守卫", "1D1"))));

        assertAutomaticallyResolved(result);
        assertThat(npc.getHpCurrent()).isEqualTo(7);
    }

    @Test
    void npcStunAutomaticallyRollsAndAppliesDuration() {
        var result = service.rollDamage(7L, 7L, new KpDiceRequestDTOs.Damage(
                "守卫受到电击", List.of(new KpDiceRequestDTOs.DamageTarget("守卫", "眩晕"))));

        assertAutomaticallyResolved(result);
        assertThat(npc.getStunnedRemainingRounds()).isBetween(1, 6);
    }

    @Test
    void npcHealingAutomaticallyRollsAndAppliesHpGain() {
        var result = service.rollHealing(7L, 7L, new KpDiceRequestDTOs.Healing(
                "守卫接受治疗", HealingSourceMode.STANDALONE, HealingMode.OTHER,
                List.of(new KpDiceRequestDTOs.HealingTarget("守卫", null, "1D1"))));

        assertAutomaticallyResolved(result);
        assertThat(npc.getHpCurrent()).isEqualTo(9);
    }

    private void assertAutomaticallyResolved(KpDiceToolResult result) {
        assertThat(result.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(result.results()).singleElement().satisfies(die -> {
            assertThat(die.getCharacterId()).isEqualTo(99L);
            assertThat(die.getResultData().getResult()).isNotNull();
            assertThat(die.getResolution().getOutcome()).isNotEmpty();
        });
    }
}
