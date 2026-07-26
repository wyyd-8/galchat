package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.vo.GroupReplyPlanVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GroupReplyPlanService {

    private static final Set<String> SOURCES = Set.of(
            GroupChatConstant.PLAN_SOURCE_USER,
            GroupChatConstant.PLAN_SOURCE_SCENE,
            GroupChatConstant.PLAN_SOURCE_COMBAT);

    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final TransactionTemplate transactionTemplate;

    public GroupReplyPlanVO getActive(Long conversationId) {
        GroupConversation conversation = conversationService.requireAuthorized(conversationId);
        return toVO(activePlan(conversation));
    }

    public GroupReplyPlanVO replace(Long conversationId, GroupReplyPlanDTO request) {
        GroupConversation conversation = conversationService.requireActive(conversationId);
        validate(conversation, request);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            return transactionTemplate.execute(status -> replaceLocked(conversation, request));
        } finally {
            lockService.unlock(lock);
        }
    }

    public GroupReplyPlanVO finishActive(Long conversationId) {
        GroupConversation conversation = conversationService.requireActive(conversationId);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            return transactionTemplate.execute(status -> {
                GroupReplyPlan active = activePlan(conversation);
                if (active == null) {
                    return GroupChatConstant.MODE_CHAT.equals(conversation.getMode())
                            ? toVO(createDefaultPlan(conversation)) : null;
                }
                return finishLocked(conversation, active);
            });
        } finally {
            lockService.unlock(lock);
        }
    }

    public GroupReplyPlanVO advanceGroup(Long conversationId) {
        GroupConversation conversation = conversationService.requireActive(conversationId);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            return transactionTemplate.execute(status -> advanceLocked(conversation));
        } finally {
            lockService.unlock(lock);
        }
    }

    /** Caller must hold the conversation lock. */
    public List<GroupReplyPlanItem> pendingItemsForExecution(GroupConversation conversation) {
        GroupReplyPlan plan = activePlan(conversation);
        if (plan == null) {
            return List.of();
        }
        return itemMapper.selectList(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, plan.getId())
                .eq(GroupReplyPlanItem::getStatus, GroupChatConstant.STATUS_PENDING)
                .orderByAsc(GroupReplyPlanItem::getGroupOrder)
                .orderByAsc(GroupReplyPlanItem::getItemOrder)
                .orderByAsc(GroupReplyPlanItem::getId));
    }

    public String activeSource(GroupConversation conversation) {
        GroupReplyPlan plan = activePlan(conversation);
        return plan == null ? GroupChatConstant.PLAN_SOURCE_USER : plan.getSource();
    }

    /** Caller must hold the conversation lock. */
    public GroupReplyPlanSelection currentGroupForExecution(GroupConversation conversation) {
        GroupReplyPlan plan = activePlan(conversation);
        if (plan == null) {
            throw new UserRequestException("当前群聊没有可执行的回复计划");
        }
        List<GroupReplyPlanItem> ordered = orderedItems(plan.getId());
        if (ordered.isEmpty()) {
            throw new UserRequestException("当前回复计划没有可执行分组");
        }
        GroupReplyPlanItem first = ordered.getFirst();
        List<GroupReplyPlanItem> current = ordered.stream()
                .filter(item -> first.getGroupKey().equals(item.getGroupKey()))
                .toList();
        return new GroupReplyPlanSelection(
                plan.getSource(),
                plan.getContextId(),
                first.getGroupKey(),
                first.getGroupName(),
                first.getGroupOrder(),
                current);
    }

    public void markRunning(GroupReplyPlanItem item) {
        updateStatus(item, GroupChatConstant.STATUS_RUNNING);
    }

    public void markCompleted(GroupReplyPlanItem item) {
        updateStatus(item, GroupChatConstant.STATUS_COMPLETED);
    }

    public void resetPending(GroupReplyPlanItem item) {
        updateStatus(item, GroupChatConstant.STATUS_PENDING);
    }

    /** 计划不进入世界存档；读档后按当前群聊成员重建。调用方必须持有相关会话锁。 */
    public void resetWorldPlans(Long userWorldId) {
        List<GroupConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<GroupConversation>()
                        .eq(GroupConversation::getUserWorldId, userWorldId));
        if (conversations.isEmpty()) {
            return;
        }
        List<Long> conversationIds = conversations.stream().map(GroupConversation::getId).toList();
        List<Long> planIds = planMapper.selectList(new LambdaQueryWrapper<GroupReplyPlan>()
                        .select(GroupReplyPlan::getId)
                        .in(GroupReplyPlan::getConversationId, conversationIds))
                .stream().map(GroupReplyPlan::getId).toList();
        if (!planIds.isEmpty()) {
            itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                    .in(GroupReplyPlanItem::getPlanId, planIds));
            planMapper.delete(new LambdaQueryWrapper<GroupReplyPlan>()
                    .in(GroupReplyPlan::getId, planIds));
        }
        for (GroupConversation conversation : conversations) {
            conversation.setActiveReplyPlanId(null).setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
            if (GroupChatConstant.STATUS_ACTIVE.equals(conversation.getStatus())) {
                createDefaultPlan(conversation);
            }
        }
    }

    /** Caller must hold the conversation lock. */
    public void clearConversationPlans(GroupConversation conversation) {
        List<Long> planIds = planMapper.selectList(new LambdaQueryWrapper<GroupReplyPlan>()
                        .select(GroupReplyPlan::getId)
                        .eq(GroupReplyPlan::getConversationId, conversation.getId()))
                .stream().map(GroupReplyPlan::getId).toList();
        if (!planIds.isEmpty()) {
            itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                    .in(GroupReplyPlanItem::getPlanId, planIds));
            planMapper.delete(new LambdaQueryWrapper<GroupReplyPlan>()
                    .in(GroupReplyPlan::getId, planIds));
        }
        conversation.setActiveReplyPlanId(null);
    }

    private GroupReplyPlanVO replaceLocked(GroupConversation conversation, GroupReplyPlanDTO request) {
        String source = request.getSource().trim().toUpperCase(Locale.ROOT);
        GroupReplyPlan active = activePlan(conversation);
        if (active != null && GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource())
                && !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(source)) {
            throw new UserRequestException("战斗计划执行期间不能覆盖探索计划，请先结束战斗");
        }
        if (active != null && GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource())
                && GroupChatConstant.PLAN_SOURCE_COMBAT.equals(source)
                && !java.util.Objects.equals(active.getContextId(), request.getContextId())) {
            throw new UserRequestException("第一版不支持在战斗中切换到另一场战斗");
        }

        boolean startsCombat = GroupChatConstant.PLAN_SOURCE_COMBAT.equals(source)
                && (active == null || !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource()));
        boolean replacesActive = active != null && !startsCombat
                && source.equals(active.getSource())
                && java.util.Objects.equals(request.getContextId(), active.getContextId());

        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan plan;
        if (replacesActive) {
            plan = active.setUpdatedAt(now);
            planMapper.updateById(plan);
            itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                    .eq(GroupReplyPlanItem::getPlanId, plan.getId()));
        } else {
            if (active != null && !startsCombat) {
                itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                        .eq(GroupReplyPlanItem::getPlanId, active.getId()));
                planMapper.deleteById(active.getId());
            }
            plan = new GroupReplyPlan()
                    .setConversationId(conversation.getId())
                    .setSource(source)
                    .setContextId(request.getContextId())
                    .setResumePlanId(startsCombat && active != null ? active.getId() : null)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            planMapper.insert(plan);
            conversation.setActiveReplyPlanId(plan.getId()).setUpdatedAt(now);
            conversationMapper.updateById(conversation);
        }
        insertItems(plan.getId(), request.getGroups(), now);
        return toVO(plan);
    }

    private GroupReplyPlanVO advanceLocked(GroupConversation conversation) {
        GroupReplyPlan active = activePlan(conversation);
        if (active == null) {
            throw new UserRequestException("当前群聊没有可推进的回复计划");
        }
        if (GroupChatConstant.PLAN_SOURCE_USER.equals(active.getSource())) {
            throw new UserRequestException("USER回复计划不支持推进分组");
        }
        List<GroupReplyPlanItem> ordered = orderedItems(active.getId());
        if (ordered.isEmpty()) {
            return finishLocked(conversation, active);
        }
        String currentGroupKey = ordered.getFirst().getGroupKey();
        itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, active.getId())
                .eq(GroupReplyPlanItem::getGroupKey, currentGroupKey));
        List<GroupReplyPlanItem> remaining = ordered.stream()
                .filter(item -> !currentGroupKey.equals(item.getGroupKey()))
                .toList();
        if (!remaining.isEmpty()) {
            return toVO(active, remaining);
        }
        return finishLocked(conversation, active);
    }

    private GroupReplyPlanVO finishLocked(GroupConversation conversation, GroupReplyPlan active) {
        itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, active.getId()));
        planMapper.deleteById(active.getId());
        if (GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource())) {
            Long resumePlanId = active.getResumePlanId();
            conversation.setActiveReplyPlanId(resumePlanId).setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
            return resumePlanId == null ? null : toVO(planMapper.selectById(resumePlanId));
        }
        if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(active.getSource())) {
            conversation.setActiveReplyPlanId(null).setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
            return null;
        }
        return toVO(createDefaultPlan(conversation));
    }

    private void insertItems(Long planId, List<GroupReplyPlanDTO.Group> groups, LocalDateTime now) {
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            GroupReplyPlanDTO.Group group = groups.get(groupIndex);
            int groupOrder = group.getOrder() == null ? groupIndex + 1 : group.getOrder();
            List<GroupReplyPlanDTO.Item> items = group.getItems();
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                GroupReplyPlanDTO.Item item = items.get(itemIndex);
                String actorType = StringUtils.hasText(item.getActorType())
                        ? item.getActorType().trim().toLowerCase(Locale.ROOT)
                        : GroupChatConstant.ACTOR_CHARACTER;
                itemMapper.insert(new GroupReplyPlanItem()
                        .setPlanId(planId)
                        .setGroupKey(group.getKey().trim())
                        .setGroupName(StringUtils.hasText(group.getName()) ? group.getName().trim() : group.getKey().trim())
                        .setGroupOrder(groupOrder)
                        .setItemOrder(item.getOrder() == null ? itemIndex + 1 : item.getOrder())
                        .setActorType(actorType)
                        .setActorId(item.getActorId())
                        .setStatus(GroupChatConstant.STATUS_PENDING)
                        .setCreatedAt(now)
                        .setUpdatedAt(now));
            }
        }
    }

    private GroupReplyPlan createDefaultPlan(GroupConversation conversation) {
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan plan = new GroupReplyPlan()
                .setConversationId(conversation.getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_USER)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        planMapper.insert(plan);
        int order = 1;
        for (GroupChatMember member : conversationService.listMembers(conversation.getId())) {
            if (!Boolean.TRUE.equals(member.getEnabled())) {
                continue;
            }
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(plan.getId())
                    .setGroupKey("default")
                    .setGroupName("群聊")
                    .setGroupOrder(1)
                    .setItemOrder(order++)
                    .setActorType(member.getActorType())
                    .setActorId(member.getActorId())
                    .setStatus(GroupChatConstant.STATUS_PENDING)
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
        conversation.setActiveReplyPlanId(plan.getId()).setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return plan;
    }

    private GroupReplyPlan activePlan(GroupConversation conversation) {
        return conversation.getActiveReplyPlanId() == null
                ? null : planMapper.selectById(conversation.getActiveReplyPlanId());
    }

    private GroupReplyPlanVO toVO(GroupReplyPlan plan) {
        if (plan == null) {
            return null;
        }
        return toVO(plan, orderedItems(plan.getId()));
    }

    private GroupReplyPlanVO toVO(GroupReplyPlan plan, List<GroupReplyPlanItem> items) {
        Map<String, List<GroupReplyPlanItem>> grouped = new LinkedHashMap<>();
        for (GroupReplyPlanItem item : items) {
            grouped.computeIfAbsent(item.getGroupKey(), ignored -> new ArrayList<>()).add(item);
        }
        List<GroupReplyPlanVO.Group> groups = grouped.values().stream().map(groupItems -> {
            GroupReplyPlanItem first = groupItems.getFirst();
            List<GroupReplyPlanVO.Item> voItems = groupItems.stream()
                    .map(item -> new GroupReplyPlanVO.Item(item.getId(), item.getItemOrder(), item.getActorType(),
                            item.getActorId()))
                    .toList();
            return new GroupReplyPlanVO.Group(first.getGroupKey(), first.getGroupName(),
                    first.getGroupOrder(), voItems);
        }).toList();
        return new GroupReplyPlanVO(plan.getId(), plan.getSource(), plan.getContextId(),
                plan.getResumePlanId(), groups);
    }

    private List<GroupReplyPlanItem> orderedItems(Long planId) {
        return itemMapper.selectList(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, planId)
                .orderByAsc(GroupReplyPlanItem::getGroupOrder)
                .orderByAsc(GroupReplyPlanItem::getItemOrder)
                .orderByAsc(GroupReplyPlanItem::getId));
    }

    private void updateStatus(GroupReplyPlanItem item, String status) {
        item.setStatus(status).setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);
    }

    private void validate(GroupConversation conversation, GroupReplyPlanDTO request) {
        if (request == null || !StringUtils.hasText(request.getSource())) {
            throw new UserRequestException("回复计划来源不能为空");
        }
        String source = request.getSource().trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(source)) {
            throw new UserRequestException("回复计划来源仅支持USER、SCENE或COMBAT");
        }
        if (GroupChatConstant.MODE_TRPG.equals(conversation.getMode())
                && GroupChatConstant.PLAN_SOURCE_USER.equals(source)) {
            throw new UserRequestException("TRPG群聊不支持USER回复计划");
        }
        if (GroupChatConstant.MODE_CHAT.equals(conversation.getMode())
                && !GroupChatConstant.PLAN_SOURCE_USER.equals(source)) {
            throw new UserRequestException("普通群聊不支持SCENE或COMBAT回复计划");
        }
        if (!GroupChatConstant.PLAN_SOURCE_USER.equals(source) && request.getContextId() == null) {
            throw new UserRequestException("SCENE或COMBAT回复计划的contextId不能为空");
        }
        if (CollectionUtils.isEmpty(request.getGroups())) {
            throw new UserRequestException("回复计划分组不能为空");
        }
        int count = 0;
        Set<String> groupKeys = new HashSet<>();
        for (GroupReplyPlanDTO.Group group : request.getGroups()) {
            if (group == null || !StringUtils.hasText(group.getKey()) || CollectionUtils.isEmpty(group.getItems())) {
                throw new UserRequestException("回复计划分组及人物不能为空");
            }
            if (!groupKeys.add(group.getKey().trim())) {
                throw new UserRequestException("回复计划分组key不能重复");
            }
            if (group.getItems().size() > GroupChatConstant.MAX_REPLY_STEPS) {
                throw new UserRequestException("单个回复计划分组人物数量不能超过"
                        + GroupChatConstant.MAX_REPLY_STEPS);
            }
            count += group.getItems().size();
            Set<String> actors = new HashSet<>();
            for (GroupReplyPlanDTO.Item item : group.getItems()) {
                if (item == null || item.getActorId() == null) {
                    throw new UserRequestException("回复人物id不能为空");
                }
                String actorType = StringUtils.hasText(item.getActorType())
                        ? item.getActorType().trim().toLowerCase(Locale.ROOT)
                        : GroupChatConstant.ACTOR_CHARACTER;
                if (!actors.add(actorType + "\n" + item.getActorId())) {
                    throw new UserRequestException("同一分组中不能重复安排同一人物");
                }
                conversationService.checkReplyMember(
                        conversation.getId(), actorType, item.getActorId(), false);
            }
        }
        if (count > 200) {
            throw new UserRequestException("回复计划人物数量不能超过200");
        }
    }

    private GroupConversationLockService.OwnedLock requireLock(Long conversationId) {
        GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
        if (lock == null) {
            throw new UserRequestException("当前群聊正在生成回复，请稍后再试");
        }
        return lock;
    }
}
