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
import org.mockito.ArgumentCaptor;
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
    void preparingAdjudicationCreatesFirstRouteAsDirectOrderedChild() {
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgCombatLifecycleService service = serviceWithSteps(stepMapper);
        GroupChatTurn turn = new GroupChatTurn().setId(30L);
        GroupChatReplyStep adjudication = new GroupChatReplyStep()
                .setId(42L).setTurnId(30L).setStepNo(2)
                .setItemOrder(2).setGroupKey("combat:1")
                .setGroupName("战斗第1轮").setGroupOrder(1)
                .setSubjectCharacterId(71L)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(stepMapper.selectList(any()))
                .thenReturn(List.of(), List.of(adjudication));
        doAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0).setId(43L);
            return 1;
        }).when(stepMapper).insert(any(GroupChatReplyStep.class));

        GroupChatReplyStep route = service.prepareAdjudicationRoot(
                turn, adjudication);

        ArgumentCaptor<GroupChatReplyStep> inserted =
                ArgumentCaptor.forClass(GroupChatReplyStep.class);
        verify(stepMapper).insert(inserted.capture());
        assertThat(route).isSameAs(inserted.getValue());
        assertThat(route.getParentStepId()).isEqualTo(42L);
        assertThat(route.getRootStepId()).isEqualTo(42L);
        assertThat(route.getActionType()).isEqualTo(
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
        assertThat(route.getSubjectCharacterId()).isEqualTo(71L);
        assertThat(route.getStepNo()).isEqualTo(3);
        assertThat(adjudication.getStatus()).isEqualTo(
                GroupChatConstant.STATUS_WAITING_INTERACTION);
    }

    @Test
    void completedClarificationAppendsRouteRetryBeforeRootCanResume() {
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgCombatLifecycleService service = serviceWithSteps(stepMapper);
        GroupChatTurn turn = new GroupChatTurn().setId(30L);
        GroupChatReplyStep adjudication = new GroupChatReplyStep()
                .setId(42L).setTurnId(30L).setStepNo(2)
                .setItemOrder(2)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION);
        GroupChatReplyStep oldRoute = new GroupChatReplyStep()
                .setId(43L).setTurnId(30L).setStepNo(3)
                .setParentStepId(42L).setRootStepId(42L)
                .setActionType(
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        GroupChatReplyStep clarification = new GroupChatReplyStep()
                .setId(44L).setTurnId(30L).setStepNo(4)
                .setParentStepId(42L).setRootStepId(42L)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        when(stepMapper.selectById(42L)).thenReturn(adjudication);
        when(stepMapper.selectList(any())).thenReturn(
                List.of(oldRoute, clarification),
                List.of(clarification),
                List.of(oldRoute, clarification));
        doAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0).setId(45L);
            return 1;
        }).when(stepMapper).insert(any(GroupChatReplyStep.class));

        GroupChatReplyStep next = service.advanceAdjudicationChild(
                turn, clarification);

        ArgumentCaptor<GroupChatReplyStep> inserted =
                ArgumentCaptor.forClass(GroupChatReplyStep.class);
        verify(stepMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getActionType()).isEqualTo(
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
        assertThat(inserted.getValue().getParentStepId()).isEqualTo(42L);
        assertThat(next).isSameAs(inserted.getValue());
        assertThat(adjudication.getStatus()).isEqualTo(
                GroupChatConstant.STATUS_WAITING_INTERACTION);
    }

    @Test
    void adjudicationPromptIncludesEveryOrderedChildUnderItsRoot() {
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        TrpgCombatLifecycleService service =
                new TrpgCombatLifecycleService(
                        conversations,
                        mock(GroupReplyPlanService.class),
                        mock(GroupReplyPlanMapper.class),
                        mock(GroupChatTurnMapper.class),
                        mock(GroupChatToolCallMapper.class),
                        stepMapper, messageMapper,
                        mock(CocCharacterMapper.class),
                        mock(TrpgCombatMapper.class),
                        JsonMapper.builder().build());
        GroupChatReplyStep attack = new GroupChatReplyStep()
                .setId(40L).setTurnId(30L).setStepNo(1)
                .setOutputMessageId(100L)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ATTACK);
        GroupChatReplyStep root = new GroupChatReplyStep()
                .setId(42L).setTurnId(30L).setStepNo(2)
                .setSubjectCharacterId(71L)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE);
        GroupChatReplyStep route1 = childStep(
                43L, 3, 42L,
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 101L);
        GroupChatReplyStep clarification = childStep(
                44L, 4, 42L,
                GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE, 102L);
        GroupChatReplyStep route2 = childStep(
                45L, 5, 42L,
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 103L);
        GroupChatReplyStep defense = childStep(
                46L, 6, 42L,
                GroupChatConstant.ACTION_COMBAT_DEFENSE, 104L);
        when(stepMapper.selectList(any())).thenReturn(
                List.of(root),
                List.of(route1, clarification, route2, defense),
                List.of(attack));
        when(messageMapper.selectById(100L)).thenReturn(
                message(100L, "向门边的食尸鬼射击"));
        when(messageMapper.selectById(101L)).thenReturn(
                message(101L, "目标不明确，需要追问"));
        when(messageMapper.selectById(102L)).thenReturn(
                message(102L, "门边那只"));
        when(messageMapper.selectById(103L)).thenReturn(
                message(103L, "目标为门边食尸鬼"));
        when(messageMapper.selectById(104L)).thenReturn(
                message(104L, "食尸鬼寻找掩护"));

        String prompt = service.adjudicationPrompt(
                7L,
                new com.me.galchat.groupchat.runtime.GroupActionSpec(
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE,
                        GroupChatConstant.ACTOR_KP, null, 71L,
                        "combat:1", "战斗", 1, 2));

        assertThat(prompt).containsSubsequence(
                "向门边的食尸鬼射击",
                "目标不明确，需要追问",
                "门边那只",
                "目标为门边食尸鬼",
                "食尸鬼寻找掩护");
    }

    @Test
    void defensePromptUsesLatestPriorRouteFromTheSameRoot() {
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        TrpgCombatLifecycleService service =
                new TrpgCombatLifecycleService(
                        mock(GroupConversationService.class),
                        mock(GroupReplyPlanService.class),
                        mock(GroupReplyPlanMapper.class),
                        mock(GroupChatTurnMapper.class),
                        mock(GroupChatToolCallMapper.class),
                        stepMapper, messageMapper,
                        mock(CocCharacterMapper.class),
                        mock(TrpgCombatMapper.class),
                        JsonMapper.builder().build());
        GroupChatReplyStep route1 = childStep(
                43L, 3, 42L,
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 101L);
        GroupChatReplyStep clarification = childStep(
                44L, 4, 42L,
                GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE, 102L);
        GroupChatReplyStep route2 = childStep(
                45L, 5, 42L,
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 103L);
        GroupChatReplyStep defense = childStep(
                46L, 6, 42L,
                GroupChatConstant.ACTION_COMBAT_DEFENSE, null)
                .setSubjectCharacterId(72L);
        when(stepMapper.selectList(any())).thenReturn(
                List.of(defense),
                List.of(route1, clarification, route2, defense));
        when(messageMapper.selectById(101L)).thenReturn(
                message(101L, "旧路由：目标不明确"));
        when(messageMapper.selectById(103L)).thenReturn(
                message(103L, "新路由：可闪避或寻找掩护"));

        String prompt = service.defensePrompt(
                new com.me.galchat.groupchat.runtime.GroupActionSpec(
                        GroupChatConstant.ACTION_COMBAT_DEFENSE,
                        GroupChatConstant.ACTOR_KP, null, 72L,
                        "combat:1", "战斗", 1, 2));

        assertThat(prompt)
                .contains("新路由：可闪避或寻找掩护")
                .doesNotContain("旧路由：目标不明确");
    }

    @Test
    void completedAdjudicationPersistsOrderedChildrenAndDerivedActiveOrder() {
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatToolCallMapper toolCalls =
                mock(GroupChatToolCallMapper.class);
        GroupChatMessageMapper messages =
                mock(GroupChatMessageMapper.class);
        TrpgCombatMapper combats = mock(TrpgCombatMapper.class);
        var objectMapper = JsonMapper.builder().build();
        TrpgCombatLifecycleService service =
                new TrpgCombatLifecycleService(
                        mock(GroupConversationService.class),
                        mock(GroupReplyPlanService.class),
                        planMapper, mock(GroupChatTurnMapper.class),
                        toolCalls, stepMapper, messages,
                        mock(CocCharacterMapper.class), combats,
                        objectMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setActiveReplyPlanId(10L);
        GroupChatTurn turn = new GroupChatTurn().setId(30L);
        GroupChatReplyStep priorRoot = new GroupChatReplyStep()
                .setId(32L).setTurnId(30L).setStepNo(2)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE);
        GroupChatReplyStep attack = new GroupChatReplyStep()
                .setId(40L).setTurnId(30L).setStepNo(3)
                .setOutputMessageId(100L)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ATTACK);
        GroupChatReplyStep root = new GroupChatReplyStep()
                .setId(42L).setTurnId(30L).setStepNo(4)
                .setItemOrder(20).setSubjectCharacterId(71L)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE);
        GroupChatReplyStep route = childStep(
                43L, 5, 42L,
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 101L);
        GroupChatReplyStep defense = childStep(
                44L, 6, 42L,
                GroupChatConstant.ACTION_COMBAT_DEFENSE, 102L);
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .setContextId(200L));
        TrpgCombat combat = new TrpgCombat()
                .setId(200L).setConversationId(7L)
                .setStatus(GroupChatConstant.COMBAT_STATUS_ACTIVE)
                .setCurrentRound(1)
                .setParticipants(objectMapper.createArrayNode())
                .setActiveTurnResults(objectMapper.createArrayNode());
        when(combats.selectById(200L)).thenReturn(combat);
        when(stepMapper.selectList(any())).thenReturn(
                List.of(route, defense),
                List.of(attack),
                List.of(priorRoot, root));
        when(toolCalls.selectList(any())).thenReturn(List.of());
        when(messages.selectById(100L)).thenReturn(
                message(100L, "攻击"));
        when(messages.selectById(101L)).thenReturn(
                message(101L, "路由"));
        when(messages.selectById(102L)).thenReturn(
                message(102L, "防守"));
        com.me.galchat.domain.po.GroupChatMessage adjudicationMessage =
                message(200L, "裁定完成");

        service.completeAdjudication(
                conversation, turn, root, adjudicationMessage);

        tools.jackson.databind.JsonNode result =
                combat.getActiveTurnResults().get(0);
        assertThat(result.get("activeOrder").asInt()).isEqualTo(2);
        assertThat(result.get("childSteps")).hasSize(2);
        assertThat(result.get("childSteps").get(0)
                .get("actionType").asText()).isEqualTo(
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
        assertThat(result.get("childSteps").get(1)
                .get("actionType").asText()).isEqualTo(
                GroupChatConstant.ACTION_COMBAT_DEFENSE);
    }

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
        GroupChatReplyStep adjudication = new GroupChatReplyStep()
                .setId(41L).setTurnId(30L).setStepNo(2)
                .setItemOrder(2)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION);
        GroupChatReplyStep route = new GroupChatReplyStep()
                .setId(42L).setTurnId(30L).setStepNo(3)
                .setParentStepId(41L).setRootStepId(41L)
                .setSubjectCharacterId(71L)
                .setActionType(
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
        when(stepMapper.selectById(41L)).thenReturn(adjudication);
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .setContextId(200L));
        when(combatMapper.selectById(200L)).thenReturn(
                new TrpgCombat().setId(200L).setConversationId(7L)
                        .setStatus(GroupChatConstant.COMBAT_STATUS_ACTIVE)
                        .setParticipants(objectMapper.createArrayNode()));
        when(stepMapper.selectList(any()))
                .thenReturn(List.of(route));
        doAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0).setId(43L);
            return 1;
        }).when(stepMapper).insert(any(GroupChatReplyStep.class));

        var decision = service.completeReactionRoute(
                conversation, turn, route,
                "{\"actionKind\":\"SELF_OR_UTILITY\","
                        + "\"insertDefense\":false,\"reason\":\"装填\"}");

        assertThat(decision.targetCharacterId()).isNull();
        assertThat(decision.targetName()).isNull();
        assertThat(decision.insertDefense()).isFalse();
        ArgumentCaptor<GroupChatReplyStep> inserted =
                ArgumentCaptor.forClass(GroupChatReplyStep.class);
        verify(stepMapper).insert(inserted.capture());
        GroupChatReplyStep defense = inserted.getValue();
        assertThat(defense.getParentStepId()).isEqualTo(41L);
        assertThat(defense.getSubjectCharacterId()).isNull();
        assertThat(defense.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_CANCELLED);
    }

    private TrpgCombatLifecycleService serviceWithSteps(
            GroupChatReplyStepMapper stepMapper) {
        return new TrpgCombatLifecycleService(
                mock(GroupConversationService.class),
                mock(GroupReplyPlanService.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupChatTurnMapper.class),
                mock(GroupChatToolCallMapper.class),
                stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(CocCharacterMapper.class),
                mock(TrpgCombatMapper.class),
                JsonMapper.builder().build());
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
    void firearmRouteCanAppendOrderedDefenseStepsForSeveralTargets() {
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
        GroupChatReplyStep adjudication = new GroupChatReplyStep()
                .setId(41L).setTurnId(30L).setStepNo(2)
                .setItemOrder(2)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION);
        GroupChatReplyStep route = new GroupChatReplyStep()
                .setId(42L).setTurnId(30L).setStepNo(3)
                .setParentStepId(41L).setRootStepId(41L)
                .setSubjectCharacterId(70L)
                .setActionType(
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
        var participants = objectMapper.createArrayNode();
        participants.addObject().put("characterId", 70L)
                .put("name", "枪手");
        participants.addObject().put("characterId", 71L)
                .put("name", "林恩");
        participants.addObject().put("characterId", 72L)
                .put("name", "陈默");
        when(stepMapper.selectById(41L)).thenReturn(adjudication);
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .setContextId(200L));
        when(combatMapper.selectById(200L)).thenReturn(
                new TrpgCombat().setId(200L).setConversationId(7L)
                        .setStatus(GroupChatConstant.COMBAT_STATUS_ACTIVE)
                        .setParticipants(participants));
        when(characterMapper.selectById(71L)).thenReturn(
                card(71L, "PLAYER", null, "林恩", 50));
        when(characterMapper.selectById(72L)).thenReturn(
                card(72L, "BOT", 88L, "陈默", 50));
        when(stepMapper.selectList(any())).thenReturn(List.of(route));
        java.util.concurrent.atomic.AtomicLong ids =
                new java.util.concurrent.atomic.AtomicLong(43L);
        doAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0)
                    .setId(ids.getAndIncrement());
            return 1;
        }).when(stepMapper).insert(any(GroupChatReplyStep.class));

        service.completeReactionRoute(
                conversation, turn, route,
                "{\"actionKind\":\"TARGETED\",\"targets\":["
                        + "{\"targetName\":\"林恩\",\"insertDefense\":true,"
                        + "\"defenseOptions\":[\"寻找掩护\"]},"
                        + "{\"targetName\":\"陈默\",\"insertDefense\":true,"
                        + "\"defenseOptions\":[\"寻找掩护\"]}]}"
        );

        ArgumentCaptor<GroupChatReplyStep> inserted =
                ArgumentCaptor.forClass(GroupChatReplyStep.class);
        verify(stepMapper, org.mockito.Mockito.times(2))
                .insert(inserted.capture());
        assertThat(inserted.getAllValues())
                .extracting(GroupChatReplyStep::getSubjectCharacterId)
                .containsExactly(71L, 72L);
        assertThat(inserted.getAllValues())
                .extracting(GroupChatReplyStep::getStatus)
                .containsOnly(GroupChatConstant.STATUS_PENDING);
    }

    @Test
    void onlyDeclaredAttackerIsPrioritizedAndOnlyInFirstRound() {
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
        CocCharacter undeclaredInvestigator = card(
                74L, "BOT", 10L, "赵雅", 70);
        when(conversations.requireActive(7L))
                .thenReturn(conversation);
        when(planMapper.selectById(10L)).thenReturn(scene);
        when(stepMapper.selectById(40L)).thenReturn(step);
        when(turnMapper.selectById(30L)).thenReturn(turn);
        when(combatMapper.selectCount(any())).thenReturn(0L);
        when(characterMapper.selectList(any()))
                .thenReturn(List.of(
                        slowPlayer, fastNpc, bot,
                        undeclaredInvestigator),
                        List.of(slowPlayer, fastNpc, bot,
                                undeclaredInvestigator));
        doAnswer(invocation -> {
            invocation.<TrpgCombat>getArgument(0).setId(200L);
            return 1;
        }).when(combatMapper).insert(any(TrpgCombat.class));
        var requested = service.requestStart(
                7L, 40L,
                List.of("林恩", "食尸鬼", "陈默", "赵雅"),
                GroupChatConstant.COMBAT_ORDER_INVESTIGATORS_FIRST,
                List.of("林恩", "陈默"));
        org.mockito.ArgumentCaptor<TrpgCombat> combatCaptor =
                org.mockito.ArgumentCaptor.forClass(TrpgCombat.class);
        verify(combatMapper).insert(combatCaptor.capture());
        assertThat(combatCaptor.getValue().getParticipants())
                .filteredOn(node -> node.path(
                        "declaredFirstRoundAttack").asBoolean(false))
                .extracting(node -> node.get("name").asText())
                .containsExactly("林恩", "陈默");
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
                .containsExactly(73L, 71L, 72L, 74L);
        assertThat(order.getValue().get(2))
                .extracting(
                        GroupReplyPlanService.CombatPlanItem::actorType,
                        GroupReplyPlanService.CombatPlanItem::actorId)
                .containsExactly(GroupChatConstant.ACTOR_KP, null);

        conversation.setActiveReplyPlanId(11L);
        when(planMapper.selectById(11L)).thenReturn(
                new GroupReplyPlan().setId(11L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .setContextId(200L));
        when(combatMapper.selectById(200L))
                .thenReturn(combatCaptor.getValue());

        service.startNextRoundUnderLock(conversation);

        org.mockito.ArgumentCaptor<
                List<GroupReplyPlanService.CombatPlanItem>> secondRound =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(plans).replaceCombatRoundUnderLock(
                org.mockito.ArgumentMatchers.eq(conversation),
                org.mockito.ArgumentMatchers.eq(2),
                secondRound.capture());
        assertThat(secondRound.getValue())
                .extracting(
                        GroupReplyPlanService.CombatPlanItem
                                ::subjectCharacterId)
                .containsExactly(72L, 74L, 73L, 71L);
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
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE, 71L),
                combatStep(43L, 3,
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE, 71L)
                        .setItemOrder(2).setParentStepId(42L)
                        .setRootStepId(42L),
                combatStep(44L, 4,
                        GroupChatConstant.ACTION_COMBAT_DEFENSE, null)
                        .setItemOrder(2).setParentStepId(42L)
                        .setRootStepId(42L),
                combatStep(45L, 3,
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

    private GroupChatReplyStep childStep(
            Long id, int stepNo, Long rootId,
            String actionType, Long outputMessageId) {
        return new GroupChatReplyStep()
                .setId(id).setTurnId(30L).setStepNo(stepNo)
                .setParentStepId(rootId).setRootStepId(rootId)
                .setActionType(actionType)
                .setOutputMessageId(outputMessageId)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
    }

    private com.me.galchat.domain.po.GroupChatMessage message(
            Long id, String content) {
        return new com.me.galchat.domain.po.GroupChatMessage()
                .setId(id).setContent(content);
    }
}
