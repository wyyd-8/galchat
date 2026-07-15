package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupReplyPlanServiceTest {

    @Test
    void combatPlanRemembersExplorationPlan() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan exploration = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(10L)).thenReturn(exploration);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ((GroupReplyPlan) invocation.getArgument(0)).setId(20L);
            return 1;
        }).when(fixture.planMapper).insert(any(GroupReplyPlan.class));

        var result = fixture.service.replace(7L, combatRequest());

        assertThat(result.getId()).isEqualTo(20L);
        assertThat(result.getResumePlanId()).isEqualTo(10L);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(20L);
        verify(fixture.planMapper, never()).deleteById(10L);
    }

    @Test
    void finishingCombatRestoresExplorationPlan() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(20L);
        GroupReplyPlan combat = new GroupReplyPlan()
                .setId(20L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setContextId(200L)
                .setResumePlanId(10L);
        GroupReplyPlan exploration = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(20L)).thenReturn(combat);
        when(fixture.planMapper.selectById(10L)).thenReturn(exploration);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(new GroupReplyPlanItem()
                .setId(11L)
                .setPlanId(10L)
                .setGroupKey("scene:100")
                .setGroupName("地下室")
                .setGroupOrder(1)
                .setItemOrder(2)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L)
                .setStatus(GroupChatConstant.STATUS_PENDING)));

        var result = fixture.service.finishActive(7L);

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getGroups().getFirst().getItems().getFirst().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(10L);
        verify(fixture.planMapper).deleteById(20L);
    }

    @Test
    void updatingCombatOrderKeepsCompletedItemsCompleted() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(20L);
        GroupReplyPlan combat = new GroupReplyPlan()
                .setId(20L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setContextId(200L)
                .setResumePlanId(10L);
        GroupReplyPlanItem completed = new GroupReplyPlanItem()
                .setId(21L)
                .setPlanId(20L)
                .setGroupKey("round:1")
                .setGroupName("第1轮")
                .setGroupOrder(1)
                .setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(20L)).thenReturn(combat);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(completed));
        var itemCaptor = org.mockito.ArgumentCaptor.forClass(GroupReplyPlanItem.class);

        fixture.service.replace(7L, combatRequest());

        verify(fixture.itemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getStatus()).isEqualTo(GroupChatConstant.STATUS_COMPLETED);
    }

    @Test
    void finishingNormalPlanRestoresEnabledMemberOrder() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.conversationService.listMembers(7L)).thenReturn(List.of(
                member(9L, true), member(8L, false), member(7L, true)));
        when(fixture.planMapper.selectById(10L)).thenReturn(scene);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ((GroupReplyPlan) invocation.getArgument(0)).setId(30L);
            return 1;
        }).when(fixture.planMapper).insert(any(GroupReplyPlan.class));
        var itemCaptor = org.mockito.ArgumentCaptor.forClass(GroupReplyPlanItem.class);

        var result = fixture.service.finishActive(7L);

        assertThat(result.getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_USER);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(30L);
        verify(fixture.itemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues()).extracting(GroupReplyPlanItem::getActorId)
                .containsExactly(9L, 7L);
    }

    private GroupReplyPlanDTO combatRequest() {
        GroupReplyPlanDTO.Item actor = new GroupReplyPlanDTO.Item();
        actor.setActorId(9L);
        GroupReplyPlanDTO.Group round = new GroupReplyPlanDTO.Group();
        round.setKey("round:1");
        round.setName("第1轮");
        round.setItems(List.of(actor));
        GroupReplyPlanDTO request = new GroupReplyPlanDTO();
        request.setSource(GroupChatConstant.PLAN_SOURCE_COMBAT);
        request.setContextId(200L);
        request.setGroups(List.of(round));
        return request;
    }

    private GroupChatMember member(Long actorId, boolean enabled) {
        return new GroupChatMember()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId)
                .setEnabled(enabled);
    }

    private static class Fixture {
        private final GroupConversationService conversationService = mock(GroupConversationService.class);
        private final GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        private final GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        private final GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        private final GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        private final GroupReplyPlanService service = new GroupReplyPlanService(conversationService, lockService,
                conversationMapper, planMapper, itemMapper, transactionTemplate);

        private Fixture() {
            when(lockService.tryLock(7L)).thenReturn(
                    new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
        }
    }
}
