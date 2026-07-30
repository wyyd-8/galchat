package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot activeSnapshot = snapshot.getActivePlan();
            if (activeSnapshot != null) {
                validatePlanTree(conversation, activeSnapshot,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            validatedSnapshots.add(new ValidatedSnapshot(conversation, activeSnapshot));
        }

        for (ValidatedSnapshot validated : validatedSnapshots) {
            GroupConversation conversation = validated.conversation();
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot activeSnapshot = validated.activeSnapshot();
            clearCurrentPlans(conversation.getId());

            Long activePlanId = null;
            if (activeSnapshot != null) {
                if (GroupChatConstant.PLAN_SOURCE_COMBAT.equalsIgnoreCase(
                        activeSnapshot.getSource())) {
                    Long resumePlanId = activeSnapshot.getResumePlan() == null
                            ? null : insertSceneChain(
                                    conversation.getId(),
                                    activeSnapshot.getResumePlan());
                    activePlanId = insertPlan(
                            conversation.getId(), activeSnapshot,
                            resumePlanId, null);
                } else {
                    activePlanId = insertSceneChain(
                            conversation.getId(), activeSnapshot);
                }
            }

            conversation.setActiveReplyPlanId(activePlanId)
                    .setStatus(GroupChatConstant.STATUS_ACTIVE)
                    .setClosedAt(null)
                    .setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
    }

    private void validatePlanTree(
            GroupConversation conversation,
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot,
            Set<UserWorldSaveSnapshotDTO.ReplyPlanSnapshot> visited) {
        if (!visited.add(snapshot)) {
            throw new UserRequestException("群聊回复计划存档包含循环引用");
        }
        replyPlanService.validateStructure(
                conversation, toPlanDTO(snapshot));
        boolean combat = GroupChatConstant.PLAN_SOURCE_COMBAT
                .equalsIgnoreCase(snapshot.getSource());
        boolean scene = GroupChatConstant.PLAN_SOURCE_SCENE
                .equalsIgnoreCase(snapshot.getSource());
        if (snapshot.getResumePlan() != null) {
            if (!combat) {
                throw new UserRequestException("只有战斗回复计划可以携带恢复计划");
            }
            if (!GroupChatConstant.PLAN_SOURCE_SCENE.equalsIgnoreCase(
                    snapshot.getResumePlan().getSource())) {
                throw new UserRequestException("战斗回复计划只能恢复探索回复计划");
            }
            validatePlanTree(
                    conversation, snapshot.getResumePlan(), visited);
        }
        if (snapshot.getNextPlan() != null) {
            if (!scene
                    || !GroupChatConstant.PLAN_SOURCE_SCENE.equalsIgnoreCase(
                            snapshot.getNextPlan().getSource())) {
                throw new UserRequestException("只有探索回复计划可以串联下一个探索计划");
            }
            validatePlanTree(
                    conversation, snapshot.getNextPlan(), visited);
        }
        if (combat && snapshot.getNextPlan() != null) {
            throw new UserRequestException("战斗回复计划不能直接串联下一个场景");
        }
    }

    private GroupReplyPlanDTO toPlanDTO(UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot) {
        GroupReplyPlanDTO dto = new GroupReplyPlanDTO();
        dto.setSource(snapshot.getSource());
        dto.setContextId(snapshot.getContextId());
        dto.setGroups(safe(snapshot.getGroups()).stream().map(groupSnapshot -> {
            if (groupSnapshot == null) {
                return (GroupReplyPlanDTO.Group) null;
            }
            GroupReplyPlanDTO.Group group = new GroupReplyPlanDTO.Group();
            group.setKey(groupSnapshot.getKey());
            group.setName(groupSnapshot.getName());
            group.setOrder(groupSnapshot.getOrder());
            group.setItems(safe(groupSnapshot.getItems()).stream().map(itemSnapshot -> {
                if (itemSnapshot == null) {
                    return (GroupReplyPlanDTO.Item) null;
                }
                GroupReplyPlanDTO.Item item = new GroupReplyPlanDTO.Item();
                item.setOrder(itemSnapshot.getOrder());
                item.setActorType(itemSnapshot.getActorType());
                item.setActorId(itemSnapshot.getActorId());
                item.setSubjectCharacterId(
                        itemSnapshot.getSubjectCharacterId());
                return item;
            }).toList());
            return group;
        }).toList());
        return dto;
    }

    private UserWorldSaveSnapshotDTO.ReplyPlanSnapshot captureActivePlan(GroupConversation conversation) {
        if (conversation.getActiveReplyPlanId() == null) {
            return null;
        }
        GroupReplyPlan active = requirePlan(conversation, conversation.getActiveReplyPlanId());
        if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                active.getSource())) {
            return captureSceneChain(
                    conversation, active, new HashSet<>());
        }
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot =
                structuralSnapshot(active);
        if (active.getNextPlanId() != null) {
            throw new UserRequestException("战斗回复计划不能直接串联下一个场景");
        }
        if (active.getResumePlanId() == null) {
            return snapshot;
        }
        GroupReplyPlan resume = requirePlan(conversation, active.getResumePlanId());
        if (!GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource())
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                        resume.getSource())) {
            throw new UserRequestException("战斗回复计划只能恢复探索回复计划");
        }
        return snapshot.setResumePlan(captureSceneChain(
                conversation, resume, new HashSet<>()));
    }

    private UserWorldSaveSnapshotDTO.ReplyPlanSnapshot captureSceneChain(
            GroupConversation conversation,
            GroupReplyPlan plan,
            Set<Long> visitedPlanIds) {
        if (!GroupChatConstant.PLAN_SOURCE_SCENE.equals(plan.getSource())) {
            throw new UserRequestException("场景链只能包含探索回复计划");
        }
        if (plan.getResumePlanId() != null) {
            throw new UserRequestException("不支持嵌套战斗恢复计划");
        }
        if (!visitedPlanIds.add(plan.getId())) {
            throw new UserRequestException("群聊回复计划数据包含循环场景链");
        }
        UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot =
                structuralSnapshot(plan);
        if (plan.getNextPlanId() == null) {
            return snapshot;
        }
        GroupReplyPlan next = requirePlan(
                conversation, plan.getNextPlanId());
        return snapshot.setNextPlan(captureSceneChain(
                conversation, next, visitedPlanIds));
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
                                            .setActorId(item.getActorId())
                                            .setSubjectCharacterId(
                                                    item.getSubjectCharacterId()))
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

    private Long insertSceneChain(
            Long conversationId,
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot) {
        Long nextPlanId = snapshot.getNextPlan() == null
                ? null : insertSceneChain(
                        conversationId, snapshot.getNextPlan());
        return insertPlan(
                conversationId, snapshot, null, nextPlanId);
    }

    private Long insertPlan(
            Long conversationId,
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot snapshot,
            Long resumePlanId,
            Long nextPlanId) {
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan plan = new GroupReplyPlan()
                .setConversationId(conversationId)
                .setSource(snapshot.getSource().trim().toUpperCase(Locale.ROOT))
                .setContextId(snapshot.getContextId())
                .setResumePlanId(resumePlanId)
                .setNextPlanId(nextPlanId)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        planMapper.insert(plan);
        List<UserWorldSaveSnapshotDTO.ReplyPlanGroupSnapshot> groups = safe(snapshot.getGroups());
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            UserWorldSaveSnapshotDTO.ReplyPlanGroupSnapshot group = groups.get(groupIndex);
            String groupKey = group.getKey().trim();
            List<UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot> items = safe(group.getItems());
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                UserWorldSaveSnapshotDTO.ReplyPlanItemSnapshot item = items.get(itemIndex);
                itemMapper.insert(new GroupReplyPlanItem()
                        .setPlanId(plan.getId())
                        .setGroupKey(groupKey)
                        .setGroupName(StringUtils.hasText(group.getName()) ? group.getName().trim() : groupKey)
                        .setGroupOrder(group.getOrder() == null ? groupIndex + 1 : group.getOrder())
                        .setItemOrder(item.getOrder() == null ? itemIndex + 1 : item.getOrder())
                        .setActorType(StringUtils.hasText(item.getActorType())
                                ? item.getActorType().trim().toLowerCase(Locale.ROOT)
                                : GroupChatConstant.ACTOR_CHARACTER)
                        .setActorId(item.getActorId())
                        .setSubjectCharacterId(
                                item.getSubjectCharacterId())
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

    private record ValidatedSnapshot(
            GroupConversation conversation,
            UserWorldSaveSnapshotDTO.ReplyPlanSnapshot activeSnapshot) {
    }
}
