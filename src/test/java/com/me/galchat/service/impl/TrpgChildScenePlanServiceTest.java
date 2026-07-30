package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
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
        AtomicLong ids = new AtomicLong(40L);
        when(planMapper.insert(any(GroupReplyPlan.class)))
                .thenAnswer(invocation -> {
            invocation.<GroupReplyPlan>getArgument(0)
                    .setId(ids.incrementAndGet());
            return 1;
        });
        TrpgChildScenePlanService service =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper);
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
                new CocModuleLocation().setId(22L).setName("阁楼"),
                List.of(
                        item(GroupChatConstant.ACTOR_USER, 101L, 1),
                        item(GroupChatConstant.ACTOR_CHARACTER, 9L, 2)));

        assertThat(child.getParentPlanId()).isEqualTo(31L);
        assertThat(child.getContextId()).isEqualTo(22L);
        assertThat(conversation.getActiveReplyPlanId())
                .isEqualTo(child.getId());
        ArgumentCaptor<GroupReplyPlanItem> captor =
                ArgumentCaptor.forClass(GroupReplyPlanItem.class);
        verify(itemMapper,
                org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(GroupReplyPlanItem::getParticipantStatus)
                .containsExactly(
                        GroupChatConstant.PARTICIPANT_ACTIVE,
                        GroupChatConstant.PARTICIPANT_ACTIVE,
                        GroupChatConstant.PARTICIPANT_ACTIVE);
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
                        planMapper, itemMapper, conversationMapper);
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
        verify(conversationMapper).updateById(conversation);
    }

    private GroupReplyPlanItem item(
            String actorType, Long actorId, int order) {
        return new GroupReplyPlanItem()
                .setId((long) order)
                .setPlanId(31L)
                .setGroupKey("scene")
                .setGroupName("场景")
                .setGroupOrder(1)
                .setItemOrder(order)
                .setActorType(actorType)
                .setActorId(actorId)
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_ACTIVE);
    }
}
