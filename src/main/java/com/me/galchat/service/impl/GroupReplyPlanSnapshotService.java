package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GroupReplyPlanSnapshotService {

    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;

    public List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot> capture(Long userWorldId) {
        List<GroupConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<GroupConversation>()
                        .eq(GroupConversation::getUserWorldId, userWorldId)
                        .eq(GroupConversation::getStatus, GroupChatConstant.STATUS_ACTIVE)
                        .orderByAsc(GroupConversation::getId));
        List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot> snapshots = new ArrayList<>();
        for (GroupConversation conversation : conversations) {
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
        for (UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot snapshot : safe(snapshots)) {
            if (snapshot == null || snapshot.getConversationId() == null) {
                throw new UserRequestException("群聊回复计划存档缺少conversationId");
            }
            GroupConversation conversation = conversationMapper.selectById(snapshot.getConversationId());
            if (conversation == null || !Objects.equals(conversation.getUserWorldId(), userWorldId)) {
                throw new UserRequestException("群聊回复计划存档不属于当前用户世界");
            }
            clearCurrentPlans(conversation.getId());

            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot activeSnapshot = snapshot.getActivePlan();
            Long activePlanId = null;
            if (activeSnapshot != null) {
                UserWorldSaveSnapshotDTO.ReplyPlanSnapshot resumeSnapshot = activeSnapshot.getResumePlan();
                if (resumeSnapshot != null && resumeSnapshot.getResumePlan() != null) {
                    throw new UserRequestException("第一版不支持嵌套战斗回复计划存档");
                }
                Long resumePlanId = resumeSnapshot == null
                        ? null : insertPlan(conversation.getId(), resumeSnapshot, null);
                activePlanId = insertPlan(conversation.getId(), activeSnapshot, resumePlanId);
            }

            conversation.setActiveReplyPlanId(activePlanId)
                    .setStatus(GroupChatConstant.STATUS_ACTIVE)
                    .setClosedAt(null)
                    .setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
    }

    private UserWorldSaveSnapshotDTO.ReplyPlanSnapshot captureActivePlan(GroupConversation conversation) {
        if (conversation.getActiveReplyPlanId() == null) {
            return null;
        }
        GroupReplyPlan active = requirePlan(conversation, conversation.getActiveReplyPlanId());
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot = structuralSnapshot(active);
        if (active.getResumePlanId() == null) {
            return snapshot;
        }
        GroupReplyPlan resume = requirePlan(conversation, active.getResumePlanId());
        if (resume.getResumePlanId() != null) {
            throw new UserRequestException("第一版不支持嵌套战斗回复计划存档");
        }
        return snapshot.setResumePlan(structuralSnapshot(resume));
    }

    private GroupReplyPlan requirePlan(GroupConversation conversation, Long planId) {
        GroupReplyPlan plan = planMapper.selectById(planId);
        if (plan == null || !Objects.equals(plan.getConversationId(), conversation.getId())) {
            throw new UserRequestException("群聊回复计划数据不完整");
        }
        return plan;
    }

    private UserWorldSaveSnapshotDTO.ReplyPlanSnapshot structuralSnapshot(GroupReplyPlan plan) {
        List<GroupReplyPlanItem> items = orderedItems(plan.getId());
        Map<String, List<GroupReplyPlanItem>> grouped = new LinkedHashMap<>();
        for (GroupReplyPlanItem item : items) {
            grouped.computeIfAbsent(item.getGroupKey(), ignored -> new ArrayList<>()).add(item);
        }
        List<UserWorldSaveSnapshotDTO.ReplyPlanGroupSnapshot> groups = grouped.values().stream()
                .map(groupItems -> {
                    GroupReplyPlanItem first = groupItems.getFirst();
                    return new UserWorldSaveSnapshotDTO.ReplyPlanGroupSnapshot()
                            .setKey(first.getGroupKey())
                            .setName(first.getGroupName())
                            .setOrder(first.getGroupOrder())
                            .setItems(groupItems.stream()
                                    .map(item -> new UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot()
                                            .setOrder(item.getItemOrder())
                                            .setActorType(item.getActorType())
                                            .setActorId(item.getActorId()))
                                    .toList());
                })
                .toList();
        return new UserWorldSaveSnapshotDTO.ReplyPlanSnapshot()
                .setSource(plan.getSource())
                .setContextId(plan.getContextId())
                .setGroups(groups);
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
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot,
            Long resumePlanId) {
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan plan = new GroupReplyPlan()
                .setConversationId(conversationId)
                .setSource(snapshot.getSource())
                .setContextId(snapshot.getContextId())
                .setResumePlanId(resumePlanId)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        planMapper.insert(plan);
        for (UserWorldSaveSnapshotDTO.ReplyPlanGroupSnapshot group : safe(snapshot.getGroups())) {
            for (UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot item : safe(group.getItems())) {
                itemMapper.insert(new GroupReplyPlanItem()
                        .setPlanId(plan.getId())
                        .setGroupKey(group.getKey())
                        .setGroupName(group.getName())
                        .setGroupOrder(group.getOrder())
                        .setItemOrder(item.getOrder())
                        .setActorType(item.getActorType())
                        .setActorId(item.getActorId())
                        .setCreatedAt(now)
                        .setUpdatedAt(now));
            }
        }
        return plan.getId();
    }

    private List<GroupReplyPlanItem> orderedItems(Long planId) {
        return itemMapper.selectList(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, planId)
                .orderByAsc(GroupReplyPlanItem::getGroupOrder)
                .orderByAsc(GroupReplyPlanItem::getItemOrder)
                .orderByAsc(GroupReplyPlanItem::getId));
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
