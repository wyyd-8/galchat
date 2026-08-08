package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgSceneLifecycleServiceTest {

    @Test
    void rejectsAParentTurnAfterChildSceneActivation() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        TrpgSceneLifecycleService service =
                new TrpgSceneLifecycleService(
                        conversationService, stepMapper, turnMapper,
                        planMapper, mock(GroupReplyPlanItemMapper.class),
                        mock(GroupTurnRecoveryService.class),
                        mock(TrpgSceneProgressStore.class),
                        mock(TrpgSceneSummaryService.class),
                        mock(GroupReplyPlanService.class),
                        mock(TrpgChildScenePlanService.class),
                        mock(TrpgTemporaryInsanityService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setActiveReplyPlanId(12L);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(stepMapper.selectById(41L)).thenReturn(
                new GroupChatReplyStep().setId(41L).setTurnId(51L)
                        .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                        .setSpeakerId(9L));
        when(turnMapper.selectById(51L)).thenReturn(
                new GroupChatTurn().setId(51L).setConversationId(7L)
                        .setPlanId(10L)
                        .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setPlanContextId(21L));
        when(planMapper.selectById(12L)).thenReturn(
                new GroupReplyPlan().setId(12L)
                        .setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(21L)
                        .setParentPlanId(10L));

        assertThatThrownBy(() -> service.requestInvestigatorFinish(
                7L, 41L, 9L))
                .hasMessageContaining("场景回复计划已变化");
    }

    @Test
    void lastInvestigatorEndingSceneCancelsRemainingTurnSteps() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        TrpgSceneProgressStore progressStore =
                mock(TrpgSceneProgressStore.class);
        TrpgSceneLifecycleService service =
                new TrpgSceneLifecycleService(
                        conversationService, stepMapper, turnMapper,
                        planMapper, itemMapper, recoveryService,
                        progressStore,
                        mock(TrpgSceneSummaryService.class),
                        mock(GroupReplyPlanService.class),
                        mock(TrpgChildScenePlanService.class),
                        mock(TrpgTemporaryInsanityService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(stepMapper.selectById(41L)).thenReturn(
                new GroupChatReplyStep().setId(41L).setTurnId(51L)
                        .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                        .setSpeakerId(9L));
        when(turnMapper.selectById(51L)).thenReturn(
                new GroupChatTurn().setId(51L).setConversationId(7L)
                        .setPlanId(10L)
                        .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setPlanContextId(21L));
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L).setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(21L));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item(9L), item(8L),
                new GroupReplyPlanItem()
                        .setActorType(GroupChatConstant.ACTOR_KP)));
        when(progressStore.readyActors(7L, 10L))
                .thenReturn(Set.of(
                        "character:8", "character:9"));

        assertThat(service.requestInvestigatorFinish(
                7L, 41L, 9L)).isTrue();

        verify(progressStore).markReady(
                7L, 10L,
                new com.me.galchat.groupchat.runtime.GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L));
        verify(progressStore).requestFinish(7L, 10L);
        verify(recoveryService).cancelPendingInvestigatorSteps(
                51L, "所有调查员已结束当前场景探索");
    }

    @Test
    void completedMarkedTurnSummarizesBeforeAdvancingScenePlan() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        TrpgSceneProgressStore progressStore =
                mock(TrpgSceneProgressStore.class);
        TrpgSceneSummaryService summaryService =
                mock(TrpgSceneSummaryService.class);
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        TrpgTemporaryInsanityService insanityService =
                mock(TrpgTemporaryInsanityService.class);
        TrpgSceneLifecycleService service =
                new TrpgSceneLifecycleService(
                        conversationService,
                        mock(GroupChatReplyStepMapper.class),
                        mock(GroupChatTurnMapper.class),
                        planMapper,
                        mock(GroupReplyPlanItemMapper.class),
                        mock(GroupTurnRecoveryService.class),
                        progressStore, summaryService,
                        replyPlanService,
                        mock(TrpgChildScenePlanService.class),
                        insanityService);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setActiveReplyPlanId(10L)
                .setMode(GroupChatConstant.MODE_TRPG);
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L).setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(21L));
        when(progressStore.isFinishRequested(7L, 10L))
                .thenReturn(true);

        assertThat(service.finalizeAfterTurn(
                conversation,
                GroupChatConstant.PLAN_SOURCE_SCENE)).isTrue();

        var ordered = org.mockito.Mockito.inOrder(
                summaryService, replyPlanService,
                insanityService, progressStore);
        ordered.verify(summaryService).summarize(7L, 21L, 10L);
        ordered.verify(replyPlanService).finishActiveUnderLock(conversation);
        ordered.verify(insanityService).advanceAfterLargeScene(7L);
        ordered.verify(progressStore).clear(7L, 10L);
    }

    @Test
    void completedChildSceneReturnsItsInvestigatorsToWaitingParent() {
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        TrpgSceneProgressStore progressStore =
                mock(TrpgSceneProgressStore.class);
        TrpgSceneSummaryService summaryService =
                mock(TrpgSceneSummaryService.class);
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        TrpgChildScenePlanService childPlanService =
                mock(TrpgChildScenePlanService.class);
        TrpgTemporaryInsanityService insanityService =
                mock(TrpgTemporaryInsanityService.class);
        TrpgSceneLifecycleService service =
                new TrpgSceneLifecycleService(
                        mock(GroupConversationService.class),
                        mock(GroupChatReplyStepMapper.class),
                        mock(GroupChatTurnMapper.class),
                        planMapper,
                        mock(GroupReplyPlanItemMapper.class),
                        mock(GroupTurnRecoveryService.class),
                        progressStore, summaryService,
                        replyPlanService,
                        childPlanService,
                        insanityService);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setActiveReplyPlanId(12L)
                .setMode(GroupChatConstant.MODE_TRPG);
        GroupReplyPlan child = new GroupReplyPlan().setId(12L)
                        .setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(22L)
                        .setParentPlanId(10L);
        when(planMapper.selectById(12L)).thenReturn(child);
        when(progressStore.isFinishRequested(7L, 12L))
                .thenReturn(true);

        assertThat(service.finalizeAfterTurn(
                conversation,
                GroupChatConstant.PLAN_SOURCE_SCENE)).isTrue();

        var ordered = org.mockito.Mockito.inOrder(
                summaryService, childPlanService, progressStore);
        ordered.verify(summaryService).summarize(7L, 22L, 12L);
        ordered.verify(childPlanService).finishChildUnderLock(
                conversation, child);
        ordered.verify(progressStore).clear(7L, 12L);
        org.mockito.Mockito.verifyNoInteractions(replyPlanService);
        org.mockito.Mockito.verifyNoInteractions(insanityService);
    }

    private GroupReplyPlanItem item(Long actorId) {
        return new GroupReplyPlanItem()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId);
    }
}
