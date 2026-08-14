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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupReplyPlanSnapshotServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                GroupConversation.class);
    }

    @Test
    void captureExcludesTrpgConversationsFromWorldSave() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanSnapshotService service = service(
                conversationMapper, planMapper, itemMapper);
        GroupConversation chat = activeConversation(7L, 10L, GroupChatConstant.MODE_CHAT);
        GroupConversation trpg = activeConversation(8L, 20L, GroupChatConstant.MODE_TRPG);
        when(conversationMapper.selectList(any())).thenReturn(List.of(chat, trpg));
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L).setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_USER)
                        .setExecutionKey("default")
                        .setDisplayName("群聊"));
        when(itemMapper.selectList(any())).thenReturn(List.of(item(11L, 10L, 9L)));

        List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot> snapshots =
                service.capture(1L);

        assertThat(snapshots)
                .extracting(UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot::getConversationId)
                .containsExactly(7L);
        assertThat(snapshots.getFirst().getActivePlan())
                .extracting(
                        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot::getExecutionKey,
                        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot::getDisplayName)
                .containsExactly("default", "群聊");
        verify(planMapper, never()).selectById(20L);
    }

    @Test
    void restoreRejectsTrpgConversationBeforeDeletingPlans() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanSnapshotService service = service(
                conversationMapper, planMapper, itemMapper);
        when(conversationMapper.selectById(8L)).thenReturn(
                activeConversation(8L, 20L, GroupChatConstant.MODE_TRPG));
        UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot snapshot =
                new UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot()
                        .setConversationId(8L);

        assertThatThrownBy(() -> service.restore(1L, List.of(snapshot)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("普通群聊");
        verify(planMapper, never()).delete(any());
        verify(itemMapper, never()).delete(any());
    }

    @Test
    void captureRejectsTrpgPlanShapeInOrdinaryConversation() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanSnapshotService service = service(
                conversationMapper, planMapper, mock(GroupReplyPlanItemMapper.class));
        when(conversationMapper.selectList(any())).thenReturn(List.of(
                activeConversation(7L, 10L, GroupChatConstant.MODE_CHAT)));
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L).setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE));

        assertThatThrownBy(() -> service.capture(1L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("USER");
    }

    @Test
    void restoreReplacesOrdinaryChatPlanOnly() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        GroupReplyPlanSnapshotService service = service(
                conversationMapper, planMapper, itemMapper);
        GroupConversation conversation = activeConversation(
                7L, 90L, GroupChatConstant.MODE_CHAT);
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(planMapper.selectList(any())).thenReturn(List.of(
                new GroupReplyPlan().setId(90L).setConversationId(7L)));
        doAnswer(invocation -> {
            invocation.getArgument(0, GroupReplyPlan.class).setId(101L);
            return 1;
        }).when(planMapper).insert(any(GroupReplyPlan.class));
        UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot item =
                new UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot()
                        .setOrder(1)
                        .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                        .setActorId(9L);
        UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot snapshot =
                new UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot()
                        .setConversationId(7L)
                        .setActivePlan(new UserWorldSaveSnapshotDTO.ReplyPlanSnapshot()
                                .setSource(GroupChatConstant.PLAN_SOURCE_USER)
                                .setExecutionKey("default")
                                .setDisplayName("群聊")
                                .setItems(List.of(item)));

        service.restore(1L, List.of(snapshot));

        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(101L);
        ArgumentCaptor<GroupReplyPlan> planCaptor =
                ArgumentCaptor.forClass(GroupReplyPlan.class);
        verify(planMapper).insert(planCaptor.capture());
        assertThat(planCaptor.getValue())
                .extracting(GroupReplyPlan::getExecutionKey,
                        GroupReplyPlan::getDisplayName)
                .containsExactly("default", "群聊");
        ArgumentCaptor<GroupReplyPlanItem> itemCaptor =
                ArgumentCaptor.forClass(GroupReplyPlanItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getSubjectCharacterId()).isNull();
        assertThat(itemCaptor.getValue().getActorId()).isEqualTo(9L);
        verify(conversationMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void restoreWithoutPlanExplicitlyClearsPreviousPlanAndClosedTime() {
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanSnapshotService service = service(
                conversationMapper, planMapper,
                mock(GroupReplyPlanItemMapper.class));
        GroupConversation conversation = activeConversation(
                7L, 90L, GroupChatConstant.MODE_CHAT)
                .setClosedAt(java.time.LocalDateTime.now());
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(planMapper.selectList(any())).thenReturn(List.of(
                new GroupReplyPlan().setId(90L).setConversationId(7L)));
        var snapshot = new UserWorldSaveSnapshotDTO
                .GroupConversationPlanSnapshot()
                .setConversationId(7L);

        service.restore(1L, List.of(snapshot));

        assertThat(conversation.getActiveReplyPlanId()).isNull();
        assertThat(conversation.getClosedAt()).isNull();
        verify(conversationMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    private GroupReplyPlanSnapshotService service(
            GroupConversationMapper conversationMapper,
            GroupReplyPlanMapper planMapper,
            GroupReplyPlanItemMapper itemMapper) {
        return new GroupReplyPlanSnapshotService(
                conversationMapper, planMapper, itemMapper,
                mock(GroupReplyPlanService.class));
    }

    private GroupConversation activeConversation(
            Long id, Long activePlanId, String mode) {
        return new GroupConversation()
                .setId(id)
                .setUserWorldId(1L)
                .setActiveReplyPlanId(activePlanId)
                .setMode(mode)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
    }

    private GroupReplyPlanItem item(Long id, Long planId, Long actorId) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setPlanId(planId)
                .setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId);
    }
}
