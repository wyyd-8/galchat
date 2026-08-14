package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class GroupReplyPlanSnapshotService {

    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final GroupReplyPlanService replyPlanService;

    public List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot> capture(Long userWorldId) {
        List<GroupConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<GroupConversation>()
                        .eq(GroupConversation::getUserWorldId, userWorldId)
                        .eq(GroupConversation::getMode, GroupChatConstant.MODE_CHAT)
                        .eq(GroupConversation::getStatus, GroupChatConstant.STATUS_ACTIVE)
                        .orderByAsc(GroupConversation::getId));
        List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot> snapshots = new ArrayList<>();
        for (GroupConversation conversation : conversations) {
            if (!GroupChatConstant.MODE_CHAT.equals(conversation.getMode())) {
                continue;
            }
            snapshots.add(new UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot()
                    .setConversationId(conversation.getId())
                    .setActivePlan(captureActivePlan(conversation)));
        }
        return snapshots;
    }

    @Transactional(rollbackFor = Exception.class)
    public void restore(
            Long userWorldId,
            List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot> snapshots) {
        List<ValidatedSnapshot> validatedSnapshots = new ArrayList<>();
        Set<Long> conversationIds = new HashSet<>();
        for (UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot snapshot : safe(snapshots)) {
            if (snapshot == null || snapshot.getConversationId() == null) {
                throw new UserRequestException("群聊回复计划存档缺少conversationId");
            }
            if (!conversationIds.add(snapshot.getConversationId())) {
                throw new UserRequestException("群聊回复计划存档包含重复conversationId");
            }
            GroupConversation conversation = conversationMapper.selectById(snapshot.getConversationId());
            if (conversation == null || !Objects.equals(conversation.getUserWorldId(), userWorldId)) {
                throw new UserRequestException("群聊回复计划存档不属于当前用户世界");
            }
            if (!GroupChatConstant.MODE_CHAT.equals(conversation.getMode())) {
                throw new UserRequestException("世界存档只能恢复普通群聊回复计划");
            }
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot activeSnapshot = snapshot.getActivePlan();
            if (activeSnapshot != null) {
                validatePlan(conversation, activeSnapshot);
            }
            validatedSnapshots.add(new ValidatedSnapshot(conversation, activeSnapshot));
        }

        for (ValidatedSnapshot validated : validatedSnapshots) {
            GroupConversation conversation = validated.conversation();
            clearCurrentPlans(conversation.getId());
            Long activePlanId = validated.activeSnapshot() == null
                    ? null : insertPlan(conversation.getId(), validated.activeSnapshot());
            conversation.setActiveReplyPlanId(activePlanId)
                    .setStatus(GroupChatConstant.STATUS_ACTIVE)
                    .setClosedAt(null)
                    .setUpdatedAt(LocalDateTime.now());
            conversationMapper.update(null,
                    new LambdaUpdateWrapper<GroupConversation>()
                            .eq(GroupConversation::getId,
                                    conversation.getId())
                            .set(GroupConversation::getActiveReplyPlanId,
                                    activePlanId)
                            .set(GroupConversation::getStatus,
                                    GroupChatConstant.STATUS_ACTIVE)
                            .set(GroupConversation::getClosedAt, null)
                            .set(GroupConversation::getUpdatedAt,
                                    conversation.getUpdatedAt()));
        }
    }

    private UserWorldSaveSnapshotDTO.ReplyPlanSnapshot captureActivePlan(
            GroupConversation conversation) {
        if (conversation.getActiveReplyPlanId() == null) {
            return null;
        }
        GroupReplyPlan plan = planMapper.selectById(conversation.getActiveReplyPlanId());
        if (plan == null || !Objects.equals(plan.getConversationId(), conversation.getId())) {
            throw new UserRequestException("群聊回复计划数据不完整");
        }
        if (!GroupChatConstant.PLAN_SOURCE_USER.equals(plan.getSource())) {
            throw new UserRequestException("普通群聊存档只支持USER回复计划");
        }
        return structuralSnapshot(plan);
    }

    private void validatePlan(
            GroupConversation conversation,
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot) {
        if (!GroupChatConstant.PLAN_SOURCE_USER.equalsIgnoreCase(snapshot.getSource())) {
            throw new UserRequestException("普通群聊存档只支持USER回复计划");
        }
        replyPlanService.validateStructure(conversation, toPlanDTO(snapshot));
    }

    private GroupReplyPlanDTO toPlanDTO(UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot) {
        GroupReplyPlanDTO dto = new GroupReplyPlanDTO();
        dto.setSource(snapshot.getSource());
        dto.setExecutionKey(snapshot.getExecutionKey());
        dto.setDisplayName(snapshot.getDisplayName());
        dto.setItems(safe(snapshot.getItems()).stream().map(itemSnapshot -> {
                if (itemSnapshot == null) {
                    return (GroupReplyPlanDTO.Item) null;
                }
                GroupReplyPlanDTO.Item item = new GroupReplyPlanDTO.Item();
                item.setOrder(itemSnapshot.getOrder());
                item.setActorType(itemSnapshot.getActorType());
                item.setActorId(itemSnapshot.getActorId());
                return item;
        }).toList());
        return dto;
    }

    private UserWorldSaveSnapshotDTO.ReplyPlanSnapshot structuralSnapshot(GroupReplyPlan plan) {
        List<GroupReplyPlanItem> items = orderedItems(plan.getId());
        return new UserWorldSaveSnapshotDTO.ReplyPlanSnapshot()
                .setSource(plan.getSource())
                .setExecutionKey(plan.getExecutionKey())
                .setDisplayName(plan.getDisplayName())
                .setItems(items.stream()
                        .map(item -> new UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot()
                                .setOrder(item.getItemOrder())
                                .setActorType(item.getActorType())
                                .setActorId(item.getActorId()))
                        .toList());
    }

    private void clearCurrentPlans(Long conversationId) {
        List<Long> planIds = planMapper.selectList(new LambdaQueryWrapper<GroupReplyPlan>()
                        .eq(GroupReplyPlan::getConversationId, conversationId))
                .stream()
                .map(GroupReplyPlan::getId)
                .toList();
        if (planIds.isEmpty()) {
            return;
        }
        itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .in(GroupReplyPlanItem::getPlanId, planIds));
        planMapper.delete(new LambdaQueryWrapper<GroupReplyPlan>()
                .in(GroupReplyPlan::getId, planIds));
    }

    private Long insertPlan(
            Long conversationId,
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot) {
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan plan = new GroupReplyPlan()
                .setConversationId(conversationId)
                .setSource(GroupChatConstant.PLAN_SOURCE_USER)
                .setExecutionKey(snapshot.getExecutionKey().trim())
                .setDisplayName(snapshot.getDisplayName().trim())
                .setCreatedAt(now)
                .setUpdatedAt(now);
        planMapper.insert(plan);
        List<UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot> items =
                safe(snapshot.getItems());
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot item =
                    items.get(itemIndex);
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(plan.getId())
                    .setItemOrder(item.getOrder() == null
                            ? itemIndex + 1 : item.getOrder())
                    .setActorType(StringUtils.hasText(item.getActorType())
                            ? item.getActorType().trim().toLowerCase(Locale.ROOT)
                            : GroupChatConstant.ACTOR_CHARACTER)
                    .setActorId(item.getActorId())
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
        return plan.getId();
    }

    private List<GroupReplyPlanItem> orderedItems(Long planId) {
        return itemMapper.selectList(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, planId)
                .orderByAsc(GroupReplyPlanItem::getItemOrder)
                .orderByAsc(GroupReplyPlanItem::getId));
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ValidatedSnapshot(
            GroupConversation conversation,
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot activeSnapshot) {
    }
}
