package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupReplyPlanSnapshotServiceTest {

    @Test
    void replyPlanSnapshotCapturesKpWithNullActorId() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanSnapshotService service = new GroupReplyPlanSnapshotService(
                conversationMapper, planMapper, itemMapper, mock(GroupReplyPlanService.class));
        when(conversationMapper.selectList(any())).thenReturn(List.of(
                new GroupConversation().setId(7L).setUserWorldId(1L).setActiveReplyPlanId(10L)));
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L).setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE).setContextId(100L));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                new GroupReplyPlanItem().setPlanId(10L).setGroupKey("scene:1")
                        .setGroupName("地下室").setGroupOrder(1).setItemOrder(1)
                        .setActorType(GroupChatConstant.ACTOR_KP).setActorId(null)));

        var captured = service.capture(1L).getFirst().getActivePlan()
                .getGroups().getFirst().getItems().getFirst();

        assertThat(captured.getActorType()).isEqualTo(GroupChatConstant.ACTOR_KP);
        assertThat(captured.getActorId()).isNull();
    }

    @Test
    void captureKeepsActiveCombatAndOneResumeSceneStructurally() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanSnapshotService service =
                new GroupReplyPlanSnapshotService(conversationMapper, planMapper, itemMapper,
                        mock(GroupReplyPlanService.class));
        GroupConversation conversation = activeConversation(20L);
        GroupReplyPlan combat = plan(20L, GroupChatConstant.PLAN_SOURCE_COMBAT, 200L, 10L);
        GroupReplyPlan scene = plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, 100L, null);
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        when(planMapper.selectById(20L)).thenReturn(combat);
        when(planMapper.selectById(10L)).thenReturn(scene);
        when(itemMapper.selectList(any())).thenReturn(
                List.of(
                        item(21L, 20L, "round:2", "第2轮", 2, 1, 9L),
                        item(22L, 20L, "round:3", "第3轮", 3, 1, 8L)),
                List.of(item(11L, 10L, "scene:1", "地下室", 1, 1, 9L)));

        List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot> snapshots = service.capture(1L);

        assertThat(snapshots).hasSize(1);
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot active = snapshots.getFirst().getActivePlan();
        assertThat(active.getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_COMBAT);
        assertThat(active.getContextId()).isEqualTo(200L);
        assertThat(active.getGroups())
                .extracting(UserWorldSaveSnapshotDTO.ReplyPlanGroupSnapshot::getKey)
                .containsExactly("round:2", "round:3");
        assertThat(active.getResumePlan().getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_SCENE);
        assertThat(active.getResumePlan().getGroups().getFirst().getItems())
                .extracting(UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot::getActorId)
                .containsExactly(9L);
    }

    @Test
    void captureKeepsAllSelectedScenesInNextPlanChain() {
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanSnapshotService service =
                new GroupReplyPlanSnapshotService(
                        conversationMapper, planMapper, itemMapper,
                        mock(GroupReplyPlanService.class));
        when(conversationMapper.selectList(any()))
                .thenReturn(List.of(activeConversation(10L)));
        when(planMapper.selectById(10L)).thenReturn(
                plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE,
                        100L, null).setNextPlanId(11L));
        when(planMapper.selectById(11L)).thenReturn(
                plan(11L, GroupChatConstant.PLAN_SOURCE_SCENE,
                        101L, null));
        when(itemMapper.selectList(any())).thenReturn(
                List.of(item(10L, 10L, "scene:100",
                        "地下室", 1, 1, 9L)),
                List.of(item(11L, 11L, "scene:101",
                        "码头", 1, 1, 9L)));

        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot active =
                service.capture(1L).getFirst().getActivePlan();

        assertThat(active.getContextId()).isEqualTo(100L);
        assertThat(active.getNextPlan().getContextId()).isEqualTo(101L);
        assertThat(active.getNextPlan().getNextPlan()).isNull();
    }

    @Test
    void restoreCreatesNewResumeIdBeforeActiveIdAndReopensConversation() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanService replyPlanService = mock(GroupReplyPlanService.class);
        GroupReplyPlanSnapshotService service =
                new GroupReplyPlanSnapshotService(conversationMapper, planMapper, itemMapper,
                        replyPlanService);
        GroupConversation conversation = activeConversation(99L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_CLOSED)
                .setClosedAt(LocalDateTime.now());
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(planMapper.selectList(any())).thenReturn(List.of());
        AtomicLong ids = new AtomicLong(100L);
        List<GroupReplyPlan> insertedPlans = new ArrayList<>();
        doAnswer(invocation -> {
            GroupReplyPlan plan = invocation.getArgument(0);
            plan.setId(ids.incrementAndGet());
            insertedPlans.add(plan);
            return 1;
        }).when(planMapper).insert(any(GroupReplyPlan.class));
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot scene = planSnapshot(
                GroupChatConstant.PLAN_SOURCE_SCENE, 100L, "scene:1", "地下室", 9L);
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot combat = planSnapshot(
                GroupChatConstant.PLAN_SOURCE_COMBAT, 200L, "round:2", "第2轮", 9L)
                .setResumePlan(scene);
        UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot snapshot =
                new UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot()
                        .setConversationId(7L)
                        .setActivePlan(combat);

        service.restore(1L, List.of(snapshot));

        assertThat(insertedPlans).extracting(GroupReplyPlan::getSource)
                .containsExactly(GroupChatConstant.PLAN_SOURCE_SCENE, GroupChatConstant.PLAN_SOURCE_COMBAT);
        assertThat(insertedPlans.get(1).getResumePlanId()).isEqualTo(insertedPlans.get(0).getId());
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(insertedPlans.get(1).getId());
        assertThat(conversation.getStatus()).isEqualTo(GroupChatConstant.STATUS_ACTIVE);
        assertThat(conversation.getClosedAt()).isNull();
    }

    @Test
    void restoreCreatesNextSceneBeforePointingActiveSceneAtIt() {
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        GroupReplyPlanSnapshotService service =
                new GroupReplyPlanSnapshotService(
                        conversationMapper, planMapper, itemMapper,
                        replyPlanService);
        GroupConversation conversation = activeConversation(99L)
                .setMode(GroupChatConstant.MODE_TRPG);
        when(conversationMapper.selectById(7L))
                .thenReturn(conversation);
        when(planMapper.selectList(any())).thenReturn(List.of());
        AtomicLong ids = new AtomicLong(100L);
        List<GroupReplyPlan> insertedPlans = new ArrayList<>();
        doAnswer(invocation -> {
            GroupReplyPlan plan = invocation.getArgument(0);
            plan.setId(ids.incrementAndGet());
            insertedPlans.add(plan);
            return 1;
        }).when(planMapper).insert(any(GroupReplyPlan.class));
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot next =
                planSnapshot(GroupChatConstant.PLAN_SOURCE_SCENE,
                        101L, "scene:101", "码头", 9L);
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot active =
                planSnapshot(GroupChatConstant.PLAN_SOURCE_SCENE,
                        100L, "scene:100", "地下室", 9L)
                        .setNextPlan(next);

        service.restore(1L, List.of(
                new UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot()
                        .setConversationId(7L)
                        .setActivePlan(active)));

        assertThat(insertedPlans)
                .extracting(GroupReplyPlan::getContextId)
                .containsExactly(101L, 100L);
        assertThat(insertedPlans.get(1).getNextPlanId())
                .isEqualTo(insertedPlans.get(0).getId());
        assertThat(conversation.getActiveReplyPlanId())
                .isEqualTo(insertedPlans.get(1).getId());
    }

    @Test
    void captureRejectsNestedResumePlan() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanSnapshotService service = new GroupReplyPlanSnapshotService(
                conversationMapper, planMapper, mock(GroupReplyPlanItemMapper.class),
                mock(GroupReplyPlanService.class));
        when(conversationMapper.selectList(any())).thenReturn(List.of(activeConversation(30L)));
        when(planMapper.selectById(30L))
                .thenReturn(plan(30L, GroupChatConstant.PLAN_SOURCE_COMBAT, 300L, 20L));
        when(planMapper.selectById(20L))
                .thenReturn(plan(20L, GroupChatConstant.PLAN_SOURCE_SCENE, 200L, 10L));

        assertThatThrownBy(() -> service.capture(1L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("嵌套");
    }

    @Test
    void restoreValidatesEntireSnapshotBeforeDeletingCurrentPlans() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanService replyPlanService = mock(GroupReplyPlanService.class);
        GroupReplyPlanSnapshotService service = new GroupReplyPlanSnapshotService(
                conversationMapper, planMapper, itemMapper, replyPlanService);
        GroupConversation conversation = activeConversation(99L).setMode(GroupChatConstant.MODE_TRPG);
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot snapshot =
                new UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot()
                        .setConversationId(7L)
                        .setActivePlan(planSnapshot(
                                GroupChatConstant.PLAN_SOURCE_USER, null, "default", "群聊", 9L));
        org.mockito.Mockito.doThrow(new UserRequestException("TRPG群聊不支持USER回复计划"))
                .when(replyPlanService).validateStructure(any(GroupConversation.class), any());

        assertThatThrownBy(() -> service.restore(1L, List.of(snapshot)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("USER");
        verify(planMapper, never()).delete(any());
        verify(itemMapper, never()).delete(any());
    }

    private GroupConversation activeConversation(Long activePlanId) {
        return new GroupConversation()
                .setId(7L)
                .setUserWorldId(1L)
                .setActiveReplyPlanId(activePlanId)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
    }

    private GroupReplyPlan plan(Long id, String source, Long contextId, Long resumePlanId) {
        return new GroupReplyPlan()
                .setId(id)
                .setConversationId(7L)
                .setSource(source)
                .setContextId(contextId)
                .setResumePlanId(resumePlanId);
    }

    private GroupReplyPlanItem item(Long id, Long planId, String key, String name,
                                    int groupOrder, int itemOrder, Long actorId) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setPlanId(planId)
                .setGroupKey(key)
                .setGroupName(name)
                .setGroupOrder(groupOrder)
                .setItemOrder(itemOrder)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId);
    }

    private UserWorldSaveSnapshotDTO.ReplyPlanSnapshot planSnapshot(
            String source, Long contextId, String key, String name, Long actorId) {
        return new UserWorldSaveSnapshotDTO.ReplyPlanSnapshot()
                .setSource(source)
                .setContextId(contextId)
                .setGroups(List.of(new UserWorldSaveSnapshotDTO.ReplyPlanGroupSnapshot()
                        .setKey(key)
                        .setName(name)
                        .setOrder(1)
                        .setItems(List.of(new UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot()
                                .setOrder(1)
                                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                                .setActorId(actorId)))));
    }
}
