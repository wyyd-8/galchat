package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgCombatMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgCombatLifecycleServiceTest {

    @Test
    void utilityActionRouteAllowsReloadWithoutAnotherParticipantTarget() {
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        TrpgCombatMapper combatMapper = mock(TrpgCombatMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        var objectMapper = JsonMapper.builder().build();
        TrpgCombatLifecycleService service =
                new TrpgCombatLifecycleService(
                        mock(GroupConversationService.class),
                        mock(GroupReplyPlanService.class),
                        planMapper,
                        mock(GroupChatTurnMapper.class),
                        mock(GroupChatToolCallMapper.class),
                        stepMapper,
                        mock(GroupChatMessageMapper.class),
                        mock(CocCharacterMapper.class),
                        combatMapper,
                        objectMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setActiveReplyPlanId(10L);
        GroupChatTurn turn = new GroupChatTurn().setId(30L);
        GroupChatReplyStep route = new GroupChatReplyStep()
                .setId(42L).setTurnId(30L).setStepNo(2)
                .setSubjectCharacterId(71L)
                .setActionType(
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .setContextId(200L));
        when(combatMapper.selectById(200L)).thenReturn(
                new TrpgCombat().setId(200L).setConversationId(7L)
                        .setStatus(GroupChatConstant.COMBAT_STATUS_ACTIVE)
                        .setParticipants(objectMapper.createArrayNode()));
        GroupChatReplyStep defense = new GroupChatReplyStep()
                .setId(43L).setTurnId(30L).setStepNo(3)
                .setItemOrder(3)
                .setActionType(GroupChatConstant.ACTION_COMBAT_DEFENSE)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(stepMapper.selectList(any())).thenReturn(List.of(defense));

        var decision = service.completeReactionRoute(
                conversation, turn, route,
                "{\"actionKind\":\"SELF_OR_UTILITY\","
                        + "\"insertDefense\":false,\"reason\":\"装填\"}");

        assertThat(decision.targetCharacterId()).isNull();
        assertThat(decision.targetName()).isNull();
        assertThat(decision.insertDefense()).isFalse();
        assertThat(defense.getSubjectCharacterId()).isNull();
        assertThat(defense.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_CANCELLED);
    }

    @Test
    void npcAttackRouteAllowsAnUnconsciousOrDyingInvestigatorTarget() {
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        TrpgCombatMapper combatMapper = mock(TrpgCombatMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        var objectMapper = JsonMapper.builder().build();
        TrpgCombatLifecycleService service =
                new TrpgCombatLifecycleService(
                        mock(GroupConversationService.class),
                        mock(GroupReplyPlanService.class),
                        planMapper,
                        mock(GroupChatTurnMapper.class),
                        mock(GroupChatToolCallMapper.class),
                        stepMapper,
                        mock(GroupChatMessageMapper.class),
                        characterMapper,
                        combatMapper,
                        objectMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setActiveReplyPlanId(10L);
        GroupChatTurn turn = new GroupChatTurn().setId(30L);
        GroupChatReplyStep route = new GroupChatReplyStep()
                .setId(42L)
                .setTurnId(30L)
                .setStepNo(2)
                .setSubjectCharacterId(72L)
                .setActionType(
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
        var participants = objectMapper.createArrayNode();
        participants.addObject().put("characterId", 71L)
                .put("name", "林恩");
        participants.addObject().put("characterId", 72L)
                .put("name", "食尸鬼");
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .setContextId(200L));
        when(combatMapper.selectById(200L)).thenReturn(
                new TrpgCombat().setId(200L).setConversationId(7L)
                        .setStatus(GroupChatConstant.COMBAT_STATUS_ACTIVE)
                        .setParticipants(participants));
        when(characterMapper.selectById(71L)).thenReturn(
                card(71L, "PLAYER", null, "林恩", 50)
                        .setUnconscious(true)
                        .setDying(true));
        when(characterMapper.selectById(72L)).thenReturn(
                card(72L, "NPC", null, "食尸鬼", 60));
        GroupChatReplyStep defense = new GroupChatReplyStep()
                .setId(43L)
                .setTurnId(30L)
                .setStepNo(3)
                .setItemOrder(3)
                .setActionType(GroupChatConstant.ACTION_COMBAT_DEFENSE)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(stepMapper.selectList(any())).thenReturn(List.of(defense));

        var decision = service.completeReactionRoute(
                conversation, turn, route,
                "{\"targetName\":\"林恩\",\"insertDefense\":false}");

        assertThat(decision.targetCharacterId()).isEqualTo(71L);
        assertThat(decision.targetName()).isEqualTo("林恩");
        assertThat(decision.insertDefense()).isFalse();
        assertThat(defense.getSubjectCharacterId()).isEqualTo(71L);
        assertThat(defense.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_CANCELLED);
    }

    @Test
    void investigatorFirstOnlyChangesFirstRoundAndNpcUsesKpController() {
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupReplyPlanService plans =
                mock(GroupReplyPlanService.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        CocCharacterMapper characterMapper =
                mock(CocCharacterMapper.class);
        TrpgCombatMapper combatMapper =
                mock(TrpgCombatMapper.class);
        TrpgCombatLifecycleService service =
                new TrpgCombatLifecycleService(
                        conversations, plans, planMapper, turnMapper,
                        mock(GroupChatToolCallMapper.class),
                        stepMapper, mock(GroupChatMessageMapper.class),
                        characterMapper, combatMapper,
                        JsonMapper.builder().build());
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(10L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(30L).setConversationId(7L)
                .setPlanId(10L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setPlanContextId(100L);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(40L).setTurnId(30L)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        CocCharacter slowPlayer = card(
                71L, "PLAYER", null, "林恩", 40)
                .setUnconscious(true);
        CocCharacter fastNpc = card(
                72L, "NPC", null, "食尸鬼", 90);
        CocCharacter bot = card(
                73L, "BOT", 9L, "陈默", 60);
        when(conversations.requireActive(7L))
                .thenReturn(conversation);
        when(planMapper.selectById(10L)).thenReturn(scene);
        when(stepMapper.selectById(40L)).thenReturn(step);
        when(turnMapper.selectById(30L)).thenReturn(turn);
        when(combatMapper.selectCount(any())).thenReturn(0L);
        when(characterMapper.selectList(any()))
                .thenReturn(List.of(
                        slowPlayer, fastNpc, bot),
                        List.of(slowPlayer, fastNpc, bot));
        doAnswer(invocation -> {
            invocation.<TrpgCombat>getArgument(0).setId(200L);
            return 1;
        }).when(combatMapper).insert(any(TrpgCombat.class));
        var requested = service.requestStart(
                7L, 40L,
                List.of("林恩", "食尸鬼", "陈默"),
                GroupChatConstant
                        .COMBAT_ORDER_INVESTIGATORS_FIRST);
        org.mockito.ArgumentCaptor<TrpgCombat> combatCaptor =
                org.mockito.ArgumentCaptor.forClass(TrpgCombat.class);
        verify(combatMapper).insert(combatCaptor.capture());
        when(combatMapper.selectList(any()))
                .thenReturn(List.of(combatCaptor.getValue()));

        assertThat(service.finalizeStartAfterTurn(
                conversation, turn)).isTrue();

        org.mockito.ArgumentCaptor<
                List<GroupReplyPlanService.CombatPlanItem>> order =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(plans).startCombatUnderLock(
                org.mockito.ArgumentMatchers.eq(conversation),
                org.mockito.ArgumentMatchers.eq(requested.combatId()),
                org.mockito.ArgumentMatchers.eq(1),
                order.capture());
        assertThat(order.getValue())
                .extracting(
                        GroupReplyPlanService.CombatPlanItem
                                ::subjectCharacterId)
                .containsExactly(73L, 71L, 72L);
        assertThat(order.getValue().getLast())
                .extracting(
                        GroupReplyPlanService.CombatPlanItem::actorType,
                        GroupReplyPlanService.CombatPlanItem::actorId)
                .containsExactly(GroupChatConstant.ACTOR_KP, null);
    }

    @Test
    void enteringDyingCancelsTheCharactersWholePendingSlot() throws Exception {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgCombatLifecycleService service = new TrpgCombatLifecycleService(
                mock(GroupConversationService.class),
                mock(GroupReplyPlanService.class),
                mock(GroupReplyPlanMapper.class),
                turnMapper,
                mock(GroupChatToolCallMapper.class),
                stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(CocCharacterMapper.class),
                mock(TrpgCombatMapper.class),
                JsonMapper.builder().build());
        GroupChatTurn turn = new GroupChatTurn()
                .setId(30L)
                .setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        List<GroupChatReplyStep> steps = List.of(
                combatStep(41L, 1,
                        GroupChatConstant.ACTION_COMBAT_ATTACK, 71L),
                combatStep(42L, 2,
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 71L),
                combatStep(43L, 3,
                        GroupChatConstant.ACTION_COMBAT_DEFENSE, null),
                combatStep(44L, 4,
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE, 71L),
                combatStep(45L, 5,
                        GroupChatConstant.ACTION_COMBAT_ATTACK, 72L));
        when(stepMapper.selectList(any())).thenReturn(steps);

        Method method = service.getClass().getMethod(
                "forfeitCurrentRoundSlot", Long.class, Long.class);
        method.invoke(service, 7L, 71L);

        assertThat(steps.subList(0, 4))
                .extracting(GroupChatReplyStep::getStatus)
                .containsOnly(GroupChatConstant.STATUS_CANCELLED);
        assertThat(steps.get(4).getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        verify(stepMapper, org.mockito.Mockito.times(4))
                .updateById(any(GroupChatReplyStep.class));
    }

    private CocCharacter card(
            Long id, String actorType, Long participantId,
            String name, int dex) {
        return new CocCharacter()
                .setId(id).setRunId(7L)
                .setActorType(actorType)
                .setParticipantId(participantId)
                .setName(name).setDex(dex)
                .setHpCurrent(10).setSanCurrent(50);
    }

    private GroupChatReplyStep combatStep(
            Long id, int itemOrder, String actionType, Long characterId) {
        return new GroupChatReplyStep()
                .setId(id)
                .setTurnId(30L)
                .setItemOrder(itemOrder)
                .setStepNo(itemOrder)
                .setActionType(actionType)
                .setSubjectCharacterId(characterId)
                .setStatus(GroupChatConstant.STATUS_PENDING);
    }
}
