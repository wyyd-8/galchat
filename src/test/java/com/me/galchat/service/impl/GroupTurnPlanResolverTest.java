package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupTurnPlanResolverTest {

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
                mock(TrpgCombatLifecycleService.class));
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
                .currentGroupForExecution(conversation);
    }

    @Test
    void completedSelectionStageBuildsScenePlans() {
        TrpgSceneSelectionService selectionService =
                mock(TrpgSceneSelectionService.class);
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                mock(GroupReplyPlanService.class), selectionService,
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class));
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
                mock(TrpgCombatLifecycleService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);

        resolver.onTurnCompleted(
                conversation, GroupChatConstant.PLAN_SOURCE_SCENE);

        verify(lifecycleService).finalizeAfterTurn(
                conversation, GroupChatConstant.PLAN_SOURCE_SCENE);
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
                mock(TrpgCombatLifecycleService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        when(runLifecycle.finalizeAfterTurn(conversation))
                .thenReturn(true);

        resolver.onTurnCompleted(
                conversation,
                GroupChatConstant.TURN_SOURCE_SCENE_SELECTION);

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
        GroupTurnPlanResolver resolver = new GroupTurnPlanResolver(
                replyPlanService,
                mock(TrpgSceneSelectionService.class),
                sceneLifecycle,
                mock(TrpgRunLifecycleService.class),
                mock(TrpgCombatLifecycleService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(10L);
        GroupReplyPlanSelection selection =
                new GroupReplyPlanSelection(
                        GroupChatConstant.PLAN_SOURCE_SCENE,
                        100L, "scene:100", "地下室",
                        1, List.of());
        when(replyPlanService.currentGroupForExecution(conversation))
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
                .thenReturn(List.of(ended, kp));
        when(sceneLifecycle.remainingActions(
                7L, 100L, List.of(ended, kp)))
                .thenReturn(List.of(kp));

        var result = resolver.resolve(conversation, runtime);

        assertThat(result.actions()).containsExactly(kp);
    }
}
