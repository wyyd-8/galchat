package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import com.me.galchat.groupchat.runtime.trpg.TrpgGroupTurnPolicy;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupTurnPlanResolverTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(
                assistant, GroupReplyPlanItem.class);
        TableInfoHelper.initTableInfo(assistant, GroupReplyPlan.class);
    }

    @Test
    void trpgWithoutActivePlanUsesStandaloneSelectionStage() {
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        TrpgSceneSelectionService selectionService =
                mock(TrpgSceneSelectionService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService, selectionService,
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                mock(TrpgProposalOrderService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(null);
        GroupActionSpec kp = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION,
                GroupChatConstant.ACTOR_KP,
                null,
                "scene-selection",
                "选景",
                1,
                1);
        when(selectionService.selectionActions(conversation))
                .thenReturn(List.of(kp));

        var result = resolver.resolve(
                conversation, mock(GroupModeRuntime.class));

        assertThat(result.source())
                .isEqualTo(GroupChatConstant.TURN_SOURCE_SCENE_SELECTION);
        assertThat(result.contextId()).isNull();
        assertThat(result.actions()).containsExactly(kp);
        verify(replyPlanService, never())
                .currentPlanForExecution(conversation);
    }

    @Test
    void completedSelectionStageBuildsScenePlans() {
        TrpgSceneSelectionService selectionService =
                mock(TrpgSceneSelectionService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                mock(GroupReplyPlanService.class), selectionService,
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                mock(TrpgProposalOrderService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG);

        resolver.onTurnCompleted(
                conversation,
                GroupChatConstant.TURN_SOURCE_SCENE_SELECTION);

        verify(selectionService).finalizeSelections(conversation);
    }

    @Test
    void completedSceneTurnRunsSceneFinalization() {
        TrpgSceneLifecycleService lifecycleService =
                mock(TrpgSceneLifecycleService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                mock(GroupReplyPlanService.class),
                mock(TrpgSceneSelectionService.class),
                lifecycleService,
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                mock(TrpgProposalOrderService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);

        resolver.onTurnCompleted(
                conversation, GroupChatConstant.PLAN_SOURCE_SCENE);

        verify(lifecycleService).finalizeAfterTurn(
                conversation, GroupChatConstant.PLAN_SOURCE_SCENE);
    }

    @Test
    void completedPostCombatTransitionResumesTheExplorationScene() {
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService,
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                mock(TrpgProposalOrderService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);

        resolver.onTurnCompleted(
                conversation, GroupChatConstant.PLAN_SOURCE_POST_COMBAT);

        verify(replyPlanService).finishActiveUnderLock(conversation);
    }

    @Test
    void completedTurnConsumesAnyFirstActionReentryBridge() {
        TrpgInvestigatorSuspensionService suspensions =
                mock(TrpgInvestigatorSuspensionService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                mock(GroupReplyPlanService.class),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                mock(TrpgProposalOrderService.class));
        resolver.setSuspensionService(suspensions);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(61L).setConversationId(7L)
                .setPlanId(31L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE);

        resolver.onTurnCompleted(conversation, turn);

        verify(suspensions).completeReentriesAfterTurn(turn);
    }

    @Test
    void sceneResolverOmitsGloballySuspendedInvestigators() {
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        TrpgSceneLifecycleService lifecycle =
                mock(TrpgSceneLifecycleService.class);
        TrpgProposalOrderService proposalOrder =
                mock(TrpgProposalOrderService.class);
        TrpgInvestigatorSuspensionService suspensions =
                mock(TrpgInvestigatorSuspensionService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService,
                mock(TrpgSceneSelectionService.class), lifecycle,
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class), proposalOrder);
        resolver.setSuspensionService(suspensions);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(10L);
        GroupReplyPlanItem investigator = sceneItem(
                11L, 1, GroupChatConstant.ACTOR_CHARACTER, 8L,
                GroupChatConstant.PARTICIPANT_ACTIVE)
                .setSubjectCharacterId(108L);
        GroupReplyPlanItem kp = sceneItem(
                12L, 2, GroupChatConstant.ACTOR_KP, null,
                GroupChatConstant.PARTICIPANT_ACTIVE);
        GroupReplyPlanSelection selection = new GroupReplyPlanSelection(
                GroupChatConstant.PLAN_SOURCE_SCENE, 100L,
                "scene:10", "地下室", List.of(investigator, kp));
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupTurnPolicy policy = new TrpgGroupTurnPolicy();
        when(runtime.turnPolicy()).thenReturn(policy);
        when(replyPlanService.currentPlanForExecution(conversation))
                .thenReturn(selection);
        when(lifecycle.readyActors(7L, 10L)).thenReturn(Set.of());
        when(lifecycle.applyReadyStatuses(selection, Set.of()))
                .thenReturn(selection);
        when(proposalOrder.orderForTurn(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(replyPlanService.replaceSceneExecutionOrderUnderLock(
                any(), any(), any())).thenReturn(selection);
        when(suspensions.isUnavailable(7L, 108L, 10L))
                .thenReturn(true);

        assertThat(resolver.resolve(conversation, runtime).actions())
                .extracting(GroupActionSpec::actorType)
                .containsExactly(GroupChatConstant.ACTOR_KP);
    }

    @Test
    void completedChatTurnFinishesTheActivePlanWhileTheCallerHoldsTheLock() {
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService,
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                mock(TrpgProposalOrderService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_CHAT);

        resolver.onTurnCompleted(
                conversation, GroupChatConstant.PLAN_SOURCE_USER);

        verify(replyPlanService).finishActiveUnderLock(conversation);
    }

    @Test
    void requestedRunFinishClosesBeforeAnyNextStageIsCreated() {
        TrpgSceneSelectionService selectionService =
                mock(TrpgSceneSelectionService.class);
        TrpgSceneLifecycleService sceneLifecycle =
                mock(TrpgSceneLifecycleService.class);
        TrpgRunLifecycleService runLifecycle =
                mock(TrpgRunLifecycleService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                mock(GroupReplyPlanService.class),
                selectionService,
                sceneLifecycle,
                runLifecycle,
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                mock(TrpgProposalOrderService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(9L)
                .setPlanSource(
                        GroupChatConstant.TURN_SOURCE_SCENE_SELECTION);
        when(runLifecycle.finalizeAfterTurn(conversation, 9L))
                .thenReturn(true);

        resolver.onTurnCompleted(conversation, turn);

        verify(selectionService, never())
                .finalizeSelections(conversation);
        verify(sceneLifecycle, never())
                .finalizeAfterTurn(
                        conversation,
                        GroupChatConstant.TURN_SOURCE_SCENE_SELECTION);
    }

    @Test
    void laterSceneTurnsOmitInvestigatorsWhoAlreadyEndedExploration() {
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        TrpgSceneLifecycleService sceneLifecycle =
                mock(TrpgSceneLifecycleService.class);
        TrpgProposalOrderService proposalOrder =
                mock(TrpgProposalOrderService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService,
                mock(TrpgSceneSelectionService.class),
                sceneLifecycle,
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                proposalOrder);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(10L);
        GroupReplyPlanSelection selection =
                new GroupReplyPlanSelection(
                        GroupChatConstant.PLAN_SOURCE_SCENE,
                        100L, "scene:100", "地下室",
                        List.of());
        when(replyPlanService.currentPlanForExecution(conversation))
                .thenReturn(selection);
        GroupActionSpec ended = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_CHARACTER,
                8L, "scene:100", "地下室", 1, 1);
        GroupActionSpec kp = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_KP,
                null, "scene:100", "地下室", 1, 2);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupTurnPolicy turnPolicy = mock(GroupTurnPolicy.class);
        when(runtime.turnPolicy()).thenReturn(turnPolicy);
        when(turnPolicy.plan(conversation, selection))
                .thenReturn(List.of(kp));
        when(sceneLifecycle.readyActors(7L, 10L))
                .thenReturn(Set.of("character-card:108"));
        when(sceneLifecycle.applyReadyStatuses(
                selection, Set.of("character-card:108")))
                .thenReturn(selection);
        when(proposalOrder.orderForTurn(
                conversation, List.of(kp)))
                .thenReturn(List.of(kp));
        when(replyPlanService.replaceSceneExecutionOrderUnderLock(
                conversation, List.of(kp),
                Set.of("character-card:108")))
                .thenReturn(selection);

        var result = resolver.resolve(conversation, runtime);

        assertThat(result.actions()).containsExactly(kp);
    }

    @Test
    void completedKpTurnCommitsChildSceneBeforeNormalSceneFinalization() {
        TrpgSceneLifecycleService sceneLifecycle =
                mock(TrpgSceneLifecycleService.class);
        TrpgChildSceneCommandService childSceneCommandService =
                mock(TrpgChildSceneCommandService.class);
        TrpgProposalOrderService proposalOrderService =
                mock(TrpgProposalOrderService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                mock(GroupReplyPlanService.class),
                mock(TrpgSceneSelectionService.class),
                sceneLifecycle,
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                childSceneCommandService,
                proposalOrderService);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(61L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE);
        when(childSceneCommandService.finalizeStartAfterTurn(
                conversation, turn)).thenReturn(true);

        resolver.onTurnCompleted(conversation, turn);

        verify(proposalOrderService).onTurnCompleted(
                conversation, turn);
        verify(childSceneCommandService)
                .finalizeStartAfterTurn(conversation, turn);
        verify(sceneLifecycle, never()).finalizeAfterTurn(
                conversation, GroupChatConstant.PLAN_SOURCE_SCENE);
    }

    @Test
    void sceneTurnUsesGlobalProposalOrderAfterRemovingInactiveActors() {
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        TrpgSceneLifecycleService sceneLifecycle =
                mock(TrpgSceneLifecycleService.class);
        TrpgProposalOrderService proposalOrder =
                mock(TrpgProposalOrderService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService,
                mock(TrpgSceneSelectionService.class),
                sceneLifecycle,
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                proposalOrder);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(10L);
        GroupReplyPlanSelection selection =
                new GroupReplyPlanSelection(
                        GroupChatConstant.PLAN_SOURCE_SCENE,
                        100L, "scene:100", "地下室",
                        List.of());
        GroupReplyPlanSelection reorderedSelection =
                new GroupReplyPlanSelection(
                        GroupChatConstant.PLAN_SOURCE_SCENE,
                        100L, "scene:100", "地下室",
                        List.of());
        when(replyPlanService.currentPlanForExecution(conversation))
                .thenReturn(selection);
        GroupActionSpec user = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_USER,
                71L, "scene:100", "地下室", 1, 1);
        GroupActionSpec agent = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_CHARACTER,
                8L, "scene:100", "地下室", 1, 2);
        GroupActionSpec kp = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_KP,
                null, "scene:100", "地下室", 1, 3);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupTurnPolicy turnPolicy = mock(GroupTurnPolicy.class);
        when(runtime.turnPolicy()).thenReturn(turnPolicy);
        when(turnPolicy.plan(
                org.mockito.ArgumentMatchers.eq(conversation), any()))
                .thenReturn(
                        List.of(user, agent, kp),
                        List.of(agent, user, kp));
        when(sceneLifecycle.readyActors(7L, 10L))
                .thenReturn(Set.of());
        when(sceneLifecycle.applyReadyStatuses(
                selection, Set.of()))
                .thenReturn(selection);
        when(proposalOrder.orderForTurn(
                conversation, List.of(user, agent, kp)))
                .thenReturn(List.of(agent, user, kp));
        when(replyPlanService.replaceSceneExecutionOrderUnderLock(
                conversation, List.of(agent, user, kp), Set.of()))
                .thenReturn(reorderedSelection);

        var result = resolver.resolve(conversation, runtime);

        assertThat(result.actions())
                .containsExactly(agent, user, kp);
    }

    @Test
    void sceneTurnPersistsExecutionOrderBeforeBuildingFinalActions() {
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanService replyPlanService = new GroupReplyPlanService(
                mock(GroupConversationService.class),
                mock(GroupConversationLockService.class),
                mock(GroupConversationMapper.class),
                planMapper, itemMapper,
                mock(TrpgRuntimeChildSceneMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(TransactionTemplate.class),
                mock(TrpgParticipantService.class));
        TrpgSceneProgressStore progressStore =
                mock(TrpgSceneProgressStore.class);
        TrpgSceneLifecycleService sceneLifecycle =
                new TrpgSceneLifecycleService(
                        mock(GroupConversationService.class),
                        mock(com.me.galchat.mapper
                                .GroupChatReplyStepMapper.class),
                        mock(com.me.galchat.mapper
                                .GroupChatTurnMapper.class),
                        planMapper, itemMapper,
                        mock(GroupTurnRecoveryService.class),
                        progressStore,
                        mock(TrpgSceneSummaryService.class),
                        replyPlanService,
                        mock(TrpgChildScenePlanService.class),
                        mock(TrpgTemporaryInsanityService.class));
        TrpgProposalOrderService proposalOrder =
                mock(TrpgProposalOrderService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService,
                mock(TrpgSceneSelectionService.class),
                sceneLifecycle,
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class),
                mock(TrpgChildSceneCommandService.class),
                proposalOrder);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L)
                .setExecutionKey("scene:100")
                .setDisplayName("地下室");
        GroupReplyPlanItem user = sceneItem(
                11L, 1, GroupChatConstant.ACTOR_USER, 71L,
                GroupChatConstant.PARTICIPANT_ACTIVE);
        GroupReplyPlanItem ready = sceneItem(
                12L, 2, GroupChatConstant.ACTOR_CHARACTER, 8L,
                GroupChatConstant.PARTICIPANT_ACTIVE);
        GroupReplyPlanItem active = sceneItem(
                13L, 3, GroupChatConstant.ACTOR_CHARACTER, 9L,
                GroupChatConstant.PARTICIPANT_ACTIVE);
        GroupReplyPlanItem waiting = sceneItem(
                14L, 4, GroupChatConstant.ACTOR_CHARACTER, 10L,
                GroupChatConstant.PARTICIPANT_WAITING);
        GroupReplyPlanItem kp = sceneItem(
                15L, 5, GroupChatConstant.ACTOR_KP, null,
                GroupChatConstant.PARTICIPANT_ACTIVE);
        List<GroupReplyPlanItem> items =
                List.of(user, ready, active, waiting, kp);
        when(planMapper.selectById(10L)).thenReturn(scene);
        when(itemMapper.selectList(any())).thenReturn(items);
        when(progressStore.readyActors(7L, 10L))
                .thenReturn(Set.of(
                        "character-card:108", "character-card:110"));
        when(proposalOrder.orderForTurn(
                org.mockito.ArgumentMatchers.eq(conversation), any()))
                .thenAnswer(invocation -> {
                    List<GroupActionSpec> actions =
                            invocation.getArgument(1);
                    GroupActionSpec activeAction = actions.stream()
                            .filter(action -> java.util.Objects.equals(
                                    action.actorId(), 9L))
                            .findFirst().orElseThrow();
                    GroupActionSpec userAction = actions.stream()
                            .filter(action -> java.util.Objects.equals(
                                    action.actorId(), 71L))
                            .findFirst().orElseThrow();
                    GroupActionSpec kpAction = actions.stream()
                            .filter(action -> GroupChatConstant.ACTOR_KP
                                    .equals(action.actorType()))
                            .findFirst().orElseThrow();
                    return List.of(activeAction, userAction, kpAction);
                });

        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        when(runtime.turnPolicy()).thenReturn(new TrpgGroupTurnPolicy());

        var result = resolver.resolve(conversation, runtime);

        assertThat(result.actions()).extracting(
                        GroupActionSpec::actorType,
                        GroupActionSpec::actorId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_CHARACTER, 9L),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_USER, 71L),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_KP, null));
        assertThat(items.stream()
                .filter(item -> item.getItemOrder() <= 3)
                .sorted(java.util.Comparator.comparing(
                        GroupReplyPlanItem::getItemOrder))
                .map(GroupReplyPlanItem::getId))
                .containsExactly(13L, 11L, 15L);
        assertThat(items.stream()
                .filter(item -> item.getItemOrder() > 3)
                .map(GroupReplyPlanItem::getId))
                .containsExactlyInAnyOrder(12L, 14L);
        assertThat(ready.getParticipantStatus()).isEqualTo(
                GroupChatConstant.PARTICIPANT_READY);
        assertThat(waiting.getParticipantStatus()).isEqualTo(
                GroupChatConstant.PARTICIPANT_WAITING);
        verify(itemMapper, times(5)).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(wrapper ->
                        ((com.baomidou.mybatisplus.core.conditions.update
                                .LambdaUpdateWrapper<?>) wrapper)
                                .getSqlSet()
                                .contains("participant_status")));
    }

    private GroupReplyPlanItem sceneItem(
            Long id,
            int order,
            String actorType,
            Long actorId,
            String participantStatus) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setPlanId(10L)
                .setItemOrder(order)
                .setActorType(actorType)
                .setActorId(actorId)
                .setSubjectCharacterId(
                        GroupChatConstant.ACTOR_USER.equals(actorType)
                                ? actorId
                                : GroupChatConstant.ACTOR_CHARACTER.equals(
                                actorType) ? actorId + 100L : null)
                .setParticipantStatus(participantStatus);
    }
}
