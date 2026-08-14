package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgChildScenePlanServiceTest {

    @Test
    void createsChildPlanWithSelectedInvestigatorsAndSwitchesActivePlan() {
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgRuntimeChildSceneMapper runtimeSceneMapper =
                mock(TrpgRuntimeChildSceneMapper.class);
        AtomicLong ids = new AtomicLong(40L);
        when(planMapper.insert(any(GroupReplyPlan.class)))
                .thenAnswer(invocation -> {
            invocation.<GroupReplyPlan>getArgument(0)
                    .setId(ids.incrementAndGet());
            return 1;
        });
        TrpgChildScenePlanService service =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper,
                        runtimeSceneMapper);
        GroupConversation conversation =
                new GroupConversation().setId(7L)
                        .setActiveReplyPlanId(31L);
        GroupReplyPlan parent = new GroupReplyPlan()
                .setId(31L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(21L);

        GroupReplyPlan child = service.startChildUnderLock(
                conversation,
                parent,
                "阁楼",
                51L,
                List.of(
                        item(GroupChatConstant.ACTOR_USER, 101L, 1),
                        item(GroupChatConstant.ACTOR_CHARACTER, 9L, 2)));

        assertThat(child.getParentPlanId()).isEqualTo(31L);
        assertThat(child.getContextId()).isEqualTo(21L);
        assertThat(child.getExecutionKey()).isEqualTo("scene:41");
        assertThat(child.getDisplayName()).isEqualTo("阁楼");
        assertThat(conversation.getActiveReplyPlanId())
                .isEqualTo(child.getId());
        ArgumentCaptor<TrpgRuntimeChildScene> sceneCaptor =
                ArgumentCaptor.forClass(
                        TrpgRuntimeChildScene.class);
        verify(runtimeSceneMapper).insert(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue())
                .extracting(
                        TrpgRuntimeChildScene::getPlanId,
                        TrpgRuntimeChildScene::getConversationId,
                        TrpgRuntimeChildScene::getSceneName,
                        TrpgRuntimeChildScene::getCreatedStepId)
                .containsExactly(41L, 7L, "阁楼", 51L);
        ArgumentCaptor<GroupReplyPlanItem> captor =
                ArgumentCaptor.forClass(GroupReplyPlanItem.class);
        verify(itemMapper,
                org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(GroupReplyPlanItem::getParticipantStatus,
                        GroupReplyPlanItem::getSubjectCharacterId,
                        GroupReplyPlanItem::getSubjectCharacterName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.PARTICIPANT_ACTIVE, 101L,
                                "亨利"),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.PARTICIPANT_ACTIVE, 109L,
                                "艾琳"),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.PARTICIPANT_ACTIVE, null,
                                null));
        verify(conversationMapper).updateById(conversation);
    }

    @Test
    void closingChildMarksItsInvestigatorsWaitingInParent() {
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgRuntimeChildSceneMapper runtimeSceneMapper =
                mock(TrpgRuntimeChildSceneMapper.class);
        GroupReplyPlan parent = new GroupReplyPlan()
                .setId(31L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE);
        GroupReplyPlan child = new GroupReplyPlan()
                .setId(41L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setParentPlanId(31L);
        when(planMapper.selectById(31L)).thenReturn(parent);
        when(itemMapper.selectList(any())).thenReturn(
                List.of(
                        item(GroupChatConstant.ACTOR_USER, 101L, 1),
                        item(GroupChatConstant.ACTOR_CHARACTER, 9L, 2)),
                List.of(
                        item(GroupChatConstant.ACTOR_USER, 101L, 1),
                        item(GroupChatConstant.ACTOR_CHARACTER, 9L, 2),
                        item(GroupChatConstant.ACTOR_CHARACTER, 10L, 3)));
        TrpgChildScenePlanService service =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper,
                        runtimeSceneMapper);
        GroupConversation conversation =
                new GroupConversation().setId(7L)
                        .setActiveReplyPlanId(41L);

        GroupReplyPlan resumed = service.finishChildUnderLock(
                conversation, child);

        assertThat(resumed).isSameAs(parent);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(31L);
        ArgumentCaptor<GroupReplyPlanItem> captor =
                ArgumentCaptor.forClass(GroupReplyPlanItem.class);
        verify(itemMapper,
                org.mockito.Mockito.times(2)).updateById(
                captor.capture());
        assertThat(captor.getAllValues())
                .extracting(GroupReplyPlanItem::getParticipantStatus)
                .containsOnly(GroupChatConstant.PARTICIPANT_WAITING);
        verify(planMapper).deleteById(41L);
        verify(runtimeSceneMapper).deleteById(41L);
        verify(conversationMapper).updateById(conversation);
    }

    @Test
    void closingChildMatchesParentByBoundCardInsteadOfController() {
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlan parent = new GroupReplyPlan()
                .setId(31L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE);
        GroupReplyPlan child = new GroupReplyPlan()
                .setId(41L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setParentPlanId(31L);
        GroupReplyPlanItem childItem = item(
                GroupChatConstant.ACTOR_CHARACTER, 9L, 1)
                .setSubjectCharacterId(109L);
        GroupReplyPlanItem parentItem = item(
                GroupChatConstant.ACTOR_CHARACTER, 99L, 1)
                .setSubjectCharacterId(109L);
        when(planMapper.selectById(31L)).thenReturn(parent);
        when(itemMapper.selectList(any())).thenReturn(
                List.of(childItem), List.of(parentItem));
        TrpgChildScenePlanService service =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper,
                        mock(TrpgRuntimeChildSceneMapper.class));

        service.finishChildUnderLock(
                new GroupConversation().setId(7L)
                        .setActiveReplyPlanId(41L),
                child);

        assertThat(parentItem.getParticipantStatus())
                .isEqualTo(GroupChatConstant.PARTICIPANT_WAITING);
        verify(itemMapper).updateById(parentItem);
    }

    @Test
    void closingChildAdvancesToItsQueuedSiblingBeforeReturningToParent() {
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgRuntimeChildSceneMapper runtimeSceneMapper =
                mock(TrpgRuntimeChildSceneMapper.class);
        GroupReplyPlan parent = new GroupReplyPlan()
                .setId(31L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE);
        GroupReplyPlan nextChild = new GroupReplyPlan()
                .setId(42L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setParentPlanId(31L);
        GroupReplyPlan child = new GroupReplyPlan()
                .setId(41L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setParentPlanId(31L).setNextPlanId(42L);
        when(planMapper.selectById(31L)).thenReturn(parent);
        when(planMapper.selectById(42L)).thenReturn(nextChild);
        when(itemMapper.selectList(any())).thenReturn(
                List.of(item(GroupChatConstant.ACTOR_USER, 101L, 1)),
                List.of(
                        item(GroupChatConstant.ACTOR_USER, 101L, 1),
                        item(GroupChatConstant.ACTOR_CHARACTER, 9L, 2)));
        TrpgChildScenePlanService service =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper,
                        runtimeSceneMapper);
        GroupConversation conversation =
                new GroupConversation().setId(7L)
                        .setActiveReplyPlanId(41L);

        GroupReplyPlan resumed = service.finishChildUnderLock(
                conversation, child);

        assertThat(resumed).isSameAs(nextChild);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(42L);
        verify(planMapper).deleteById(41L);
        verify(conversationMapper).updateById(conversation);
    }

    private GroupReplyPlanItem item(
            String actorType, Long actorId, int order) {
        return new GroupReplyPlanItem()
                .setId((long) order)
                .setPlanId(31L)
                .setItemOrder(order)
                .setActorType(actorType)
                .setActorId(actorId)
                .setSubjectCharacterId(
                        GroupChatConstant.ACTOR_USER.equals(actorType)
                                ? actorId : actorId + 100L)
                .setSubjectCharacterName(
                        GroupChatConstant.ACTOR_USER.equals(actorType)
                                ? "亨利" : "艾琳")
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_ACTIVE);
    }
}
