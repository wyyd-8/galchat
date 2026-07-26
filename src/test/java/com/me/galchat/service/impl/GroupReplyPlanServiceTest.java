package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                .setMode(GroupChatConstant.MODE_TRPG)
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
                .setMode(GroupChatConstant.MODE_TRPG)
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
                .setActorId(9L)));

        var result = fixture.service.finishActive(7L);

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getGroups().getFirst().getItems().getFirst())
                .extracting(
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getId,
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getOrder,
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getActorType,
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getActorId)
                .containsExactly(11L, 2, GroupChatConstant.ACTOR_CHARACTER, 9L);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(10L);
        verify(fixture.planMapper).deleteById(20L);
    }

    @Test
    void currentGroupIsReusableAndDoesNotSelectFutureGroups() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L);
        GroupReplyPlanItem current = new GroupReplyPlanItem()
                .setId(11L)
                .setPlanId(10L)
                .setGroupKey("scene:1")
                .setGroupName("地下室")
                .setGroupOrder(1)
                .setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L);
        GroupReplyPlanItem future = new GroupReplyPlanItem()
                .setId(12L)
                .setPlanId(10L)
                .setGroupKey("scene:2")
                .setGroupName("阁楼")
                .setGroupOrder(2)
                .setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(8L);
        when(fixture.planMapper.selectById(10L)).thenReturn(scene);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(current, future));

        GroupReplyPlanSelection first = fixture.service.currentGroupForExecution(conversation);
        GroupReplyPlanSelection second = fixture.service.currentGroupForExecution(conversation);

        assertThat(first.items()).extracting(GroupReplyPlanItem::getId).containsExactly(11L);
        assertThat(second.items()).extracting(GroupReplyPlanItem::getId).containsExactly(11L);
        assertThat(first.source()).isEqualTo(GroupChatConstant.PLAN_SOURCE_SCENE);
        assertThat(first.contextId()).isEqualTo(100L);
        assertThat(first.groupKey()).isEqualTo("scene:1");
        verify(fixture.itemMapper, never()).updateById(any(GroupReplyPlanItem.class));
    }

    @Test
    void advancingSceneDeletesOnlyCurrentGroup() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(GroupChatConstant.MODE_TRPG, 10L);
        GroupReplyPlan scene = plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null);
        GroupReplyPlanItem current = item(11L, 10L, "scene:1", 1, 9L);
        GroupReplyPlanItem future = item(12L, 10L, "scene:2", 2, 8L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(10L)).thenReturn(scene);
        when(fixture.itemMapper.selectList(any()))
                .thenReturn(List.of(current, future), List.of(future));

        var result = fixture.service.advanceGroup(7L);

        assertThat(result.getGroups()).extracting(com.me.galchat.domain.vo.GroupReplyPlanVO.Group::getKey)
                .containsExactly("scene:2");
        verify(fixture.itemMapper).delete(any());
        verify(fixture.planMapper, never()).deleteById(10L);
    }

    @Test
    void advancingLastCombatGroupRestoresResumePlan() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(GroupChatConstant.MODE_TRPG, 20L);
        GroupReplyPlan combat = plan(20L, GroupChatConstant.PLAN_SOURCE_COMBAT, 10L);
        GroupReplyPlan scene = plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(20L)).thenReturn(combat);
        when(fixture.planMapper.selectById(10L)).thenReturn(scene);
        when(fixture.itemMapper.selectList(any()))
                .thenReturn(List.of(item(21L, 20L, "round:1", 1, 9L)), List.of(
                        item(11L, 10L, "scene:1", 1, 9L)));

        var result = fixture.service.advanceGroup(7L);

        assertThat(result.getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_SCENE);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(10L);
        verify(fixture.planMapper).deleteById(20L);
    }

    @Test
    void userPlanCannotAdvance() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(GroupChatConstant.MODE_CHAT, 10L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(10L))
                .thenReturn(plan(10L, GroupChatConstant.PLAN_SOURCE_USER, null));

        assertThatThrownBy(() -> fixture.service.advanceGroup(7L))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("USER");
    }

    @Test
    void finishingSceneLeavesConversationWithoutPlan() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(GroupChatConstant.MODE_TRPG, 10L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(10L))
                .thenReturn(plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null));
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of());

        var result = fixture.service.finishActive(7L);

        assertThat(result).isNull();
        assertThat(conversation.getActiveReplyPlanId()).isNull();
        verify(fixture.planMapper).deleteById(10L);
    }

    @Test
    void rejectsPlanSourceThatDoesNotMatchConversationMode() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_TRPG, null));

        assertThatThrownBy(() -> fixture.service.replace(7L, userRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("USER");

        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_CHAT, null));
        assertThatThrownBy(() -> fixture.service.replace(7L, combatRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("SCENE")
                .hasMessageContaining("COMBAT");
    }

    @Test
    void sceneAndCombatPlansRequireContextId() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_TRPG, null));
        GroupReplyPlanDTO request = combatRequest();
        request.setContextId(null);

        assertThatThrownBy(() -> fixture.service.replace(7L, request))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("contextId");
    }

    @Test
    void rejectsDisabledPlanMember() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_CHAT, null));
        org.mockito.Mockito.doThrow(new com.me.galchat.exception.UserRequestException("回复角色已被禁用"))
                .when(fixture.conversationService)
                .checkReplyMember(7L, GroupChatConstant.ACTOR_CHARACTER, 9L, false);

        assertThatThrownBy(() -> fixture.service.replace(7L, userRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("禁用");
    }

    @Test
    void rejectsGroupLargerThanReplyStepLimit() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_CHAT, null));
        GroupReplyPlanDTO.Group group = new GroupReplyPlanDTO.Group();
        group.setKey("default");
        group.setItems(IntStream.rangeClosed(1, 13)
                .mapToObj(index -> {
                    GroupReplyPlanDTO.Item item = new GroupReplyPlanDTO.Item();
                    item.setActorId((long) index);
                    return item;
                })
                .toList());
        GroupReplyPlanDTO request = new GroupReplyPlanDTO();
        request.setSource(GroupChatConstant.PLAN_SOURCE_USER);
        request.setGroups(List.of(group));

        assertThatThrownBy(() -> fixture.service.replace(7L, request))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("12");
    }

    @Test
    void replaceRejectsNonTerminalTurnBeforeMutatingPlan() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_CHAT, null));
        org.mockito.Mockito.doThrow(new com.me.galchat.exception.UserRequestException("存在未完成的群聊轮次"))
                .when(fixture.recoveryService).assertConversationHasNoNonTerminalTurns(7L);

        assertThatThrownBy(() -> fixture.service.replace(7L, userRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("未完成");
        verify(fixture.planMapper, never()).insert(any(GroupReplyPlan.class));
    }

    @Test
    void finishingNormalPlanRestoresEnabledMemberOrder() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_CHAT)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan userPlan = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_USER);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.conversationService.listMembers(7L)).thenReturn(List.of(
                member(9L, true), member(8L, false), member(7L, true)));
        when(fixture.planMapper.selectById(10L)).thenReturn(userPlan);
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

    private GroupReplyPlanDTO userRequest() {
        GroupReplyPlanDTO request = combatRequest();
        request.setSource(GroupChatConstant.PLAN_SOURCE_USER);
        request.setContextId(null);
        request.getGroups().getFirst().setKey("default");
        request.getGroups().getFirst().setName("群聊");
        return request;
    }

    private GroupConversation activeConversation(String mode, Long activePlanId) {
        return new GroupConversation()
                .setId(7L)
                .setMode(mode)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(activePlanId);
    }

    private GroupReplyPlan plan(Long id, String source, Long resumePlanId) {
        return new GroupReplyPlan()
                .setId(id)
                .setConversationId(7L)
                .setSource(source)
                .setContextId(GroupChatConstant.PLAN_SOURCE_USER.equals(source) ? null : 100L)
                .setResumePlanId(resumePlanId);
    }

    private GroupReplyPlanItem item(Long id, Long planId, String groupKey, int groupOrder, Long actorId) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setPlanId(planId)
                .setGroupKey(groupKey)
                .setGroupName(groupKey)
                .setGroupOrder(groupOrder)
                .setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId);
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
        private final GroupTurnRecoveryService recoveryService = mock(GroupTurnRecoveryService.class);
        private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        private final GroupReplyPlanService service = new GroupReplyPlanService(conversationService, lockService,
                conversationMapper, planMapper, itemMapper, recoveryService, transactionTemplate);

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
