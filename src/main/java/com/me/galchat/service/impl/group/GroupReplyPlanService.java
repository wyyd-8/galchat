package com.me.galchat.service.impl.group;

import com.me.galchat.service.impl.trpg.TrpgParticipantService;
import com.me.galchat.service.impl.trpg.TrpgSceneProgressStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.domain.vo.GroupReplyPlanVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    private final TrpgRuntimeChildSceneMapper runtimeChildSceneMapper;
    private final GroupTurnRecoveryService recoveryService;
    private final TransactionTemplate transactionTemplate;
    private final TrpgParticipantService participantService;

    public List<GroupReplyPlanVO> listRemaining(Long conversationId) {
        GroupConversation conversation = conversationService.requireAuthorized(conversationId);
        Long activePlanId = conversation.getActiveReplyPlanId();
        List<GroupReplyPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<GroupReplyPlan>()
                        .eq(GroupReplyPlan::getConversationId,
                                conversationId)
                        .orderByAsc(GroupReplyPlan::getId));
        if (activePlanId == null) {
            if (!plans.isEmpty()) {
                throw new UserRequestException(
                        "回复计划存在但会话缺少活动回复计划");
            }
            return List.of();
        }
        GroupReplyPlan active = plans.stream()
                .filter(plan -> activePlanId.equals(plan.getId()))
                .findFirst()
                .orElseThrow(() -> new UserRequestException(
                        "活动回复计划不存在"));
        List<Long> planIds = plans.stream()
                .map(GroupReplyPlan::getId)
                .toList();
        Map<Long, List<GroupReplyPlanItem>> itemsByPlanId =
                new HashMap<>();
        if (!planIds.isEmpty()) {
            for (GroupReplyPlanItem item : itemMapper.selectList(
                    new LambdaQueryWrapper<GroupReplyPlanItem>()
                            .in(GroupReplyPlanItem::getPlanId,
                                    planIds)
                            .orderByAsc(GroupReplyPlanItem::getPlanId)
                            .orderByAsc(GroupReplyPlanItem::getItemOrder)
                            .orderByAsc(GroupReplyPlanItem::getId))) {
                itemsByPlanId.computeIfAbsent(
                        item.getPlanId(), ignored -> new ArrayList<>())
                        .add(item);
            }
        }
        List<GroupReplyPlanVO> result = new ArrayList<>(plans.size());
        result.add(toVO(active, itemsByPlanId.getOrDefault(
                active.getId(), List.of())));
        plans.stream()
                .filter(plan -> !activePlanId.equals(plan.getId()))
                .map(plan -> toVO(plan, itemsByPlanId.getOrDefault(
                        plan.getId(), List.of())))
                .forEach(result::add);
        return List.copyOf(result);
    }

    public GroupReplyPlanVO replace(Long conversationId, GroupReplyPlanDTO request) {
        conversationService.requireAuthorized(conversationId);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            GroupConversation conversation = conversationService.requireActive(conversationId);
            rejectPublicTrpgMutation(conversation);
            rejectPublicCombatSource(request);
            validateStructure(conversation, request);
            recoveryService.assertConversationHasNoNonTerminalTurns(conversationId);
            return transactionTemplate.execute(status -> replaceLocked(conversation, request));
        } finally {
            lockService.unlock(lock);
        }
    }

    public GroupReplyPlanVO finishActive(Long conversationId) {
        conversationService.requireAuthorized(conversationId);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            GroupConversation conversation = conversationService.requireActive(conversationId);
            rejectPublicTrpgMutation(conversation);
            recoveryService.assertConversationHasNoNonTerminalTurns(conversationId);
            return transactionTemplate.execute(status -> {
                GroupReplyPlan active = activePlan(conversation);
                rejectPublicCombatPlan(active);
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

    /** Caller must hold the conversation lock and complete the current turn first. */
    public GroupReplyPlanVO finishActiveUnderLock(
            GroupConversation conversation) {
        if (conversation == null || conversation.getId() == null) {
            throw new UserRequestException("群聊会话不能为空");
        }
        return transactionTemplate.execute(status -> {
            GroupReplyPlan active = activePlan(conversation);
            return active == null ? null : finishLocked(
                    conversation, active);
        });
    }

    /**
     * Trusted combat lifecycle entry point. Caller must hold the conversation
     * lock and must invoke this only after the scene KP step completed.
     */
    public GroupReplyPlanVO startCombatUnderLock(
            GroupConversation conversation,
            Long combatId,
            int round,
            List<CombatPlanItem> participants) {
        if (conversation == null || conversation.getId() == null
                || combatId == null || participants == null
                || participants.isEmpty()) {
            throw new UserRequestException("战斗计划参数不完整");
        }
        GroupReplyPlan scene = activePlan(conversation);
        if (scene == null || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                scene.getSource())) {
            throw new UserRequestException("战斗只能从活动场景发起");
        }
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan combat = new GroupReplyPlan()
                .setConversationId(conversation.getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setContextId(combatId)
                .setExecutionKey(combatExecutionKey(round))
                .setDisplayName(combatDisplayName(round))
                .setResumePlanId(scene.getId())
                .setCreatedAt(now)
                .setUpdatedAt(now);
        planMapper.insert(combat);
        insertCombatItems(combat.getId(), round, participants, now);
        conversation.setActiveReplyPlanId(combat.getId())
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return toVO(combat);
    }

    /** Trusted combat lifecycle entry point. Caller must hold the lock. */
    public GroupReplyPlanVO startPostCombatTransitionUnderLock(
            GroupConversation conversation,
            Long combatId) {
        if (conversation == null || conversation.getId() == null
                || combatId == null) {
            throw new UserRequestException("战斗结束后的叙事过渡参数不完整");
        }
        GroupReplyPlan scene = activePlan(conversation);
        if (scene == null || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                scene.getSource())) {
            throw new UserRequestException(
                    "战斗结束后的叙事过渡缺少恢复场景");
        }
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan transition = new GroupReplyPlan()
                .setConversationId(conversation.getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_POST_COMBAT)
                .setContextId(combatId)
                .setExecutionKey("post-combat:" + combatId)
                .setDisplayName("战斗结束后的叙事过渡")
                .setResumePlanId(scene.getId())
                .setCreatedAt(now).setUpdatedAt(now);
        planMapper.insert(transition);
        itemMapper.insert(new GroupReplyPlanItem()
                .setPlanId(transition.getId()).setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE)
                .setCreatedAt(now).setUpdatedAt(now));
        conversation.setActiveReplyPlanId(transition.getId())
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return toVO(transition);
    }

    /** Trusted combat lifecycle entry point. Caller must hold the lock. */
    public GroupReplyPlanVO replaceCombatRoundUnderLock(
            GroupConversation conversation,
            int round,
            List<CombatPlanItem> participants) {
        GroupReplyPlan combat = activePlan(conversation);
        if (combat == null || !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                combat.getSource())) {
            throw new UserRequestException("当前没有活动战斗");
        }
        LocalDateTime now = LocalDateTime.now();
        itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, combat.getId()));
        insertCombatItems(combat.getId(), round, participants, now);
        combat.setExecutionKey(combatExecutionKey(round))
                .setDisplayName(combatDisplayName(round))
                .setUpdatedAt(now);
        planMapper.updateById(combat);
        return toVO(combat);
    }

    /** Caller must hold the conversation lock. */
    public GroupReplyPlanSelection currentPlanForExecution(GroupConversation conversation) {
        GroupReplyPlan plan = activePlan(conversation);
        if (plan == null) {
            throw new UserRequestException("当前群聊没有可执行的回复计划");
        }
        List<GroupReplyPlanItem> ordered = orderedItems(plan.getId());
        if (ordered.isEmpty()) {
            throw new UserRequestException("当前回复计划没有可执行项");
        }
        return new GroupReplyPlanSelection(
                plan.getSource(),
                plan.getContextId(),
                plan.getExecutionKey(),
                plan.getDisplayName(),
                ordered);
    }

    /**
     * Persists the next scene turn's execution order. The caller must hold
     * the conversation lock. Items excluded from the turn stay in the plan
     * as scene participants, but are shuffled behind every executable item.
     */
    public GroupReplyPlanSelection replaceSceneExecutionOrderUnderLock(
            GroupConversation conversation,
            List<GroupActionSpec> executableActions,
            Set<String> readyActors) {
        GroupReplyPlan plan = activePlan(conversation);
        if (plan == null || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                plan.getSource())) {
            throw new UserRequestException("当前没有活动探索场景");
        }
        List<GroupReplyPlanItem> remaining = new ArrayList<>(
                orderedItems(plan.getId()));
        for (GroupReplyPlanItem item : remaining) {
            if (isInvestigator(item)
                    && !GroupChatConstant.PARTICIPANT_WAITING.equals(
                            item.getParticipantStatus())
                    && readyActors.contains(
                            TrpgSceneProgressStore.actorKey(
                                    item.getSubjectCharacterId()))) {
                item.setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_READY);
            }
        }
        List<GroupReplyPlanItem> reordered = new ArrayList<>();
        for (GroupActionSpec action : executableActions) {
            GroupReplyPlanItem matched = remaining.stream()
                    .filter(item -> sameSceneActor(item, action))
                    .findFirst()
                    .orElseThrow(() -> new UserRequestException(
                            "探索执行顺序与回复计划不一致"));
            reordered.add(matched);
            remaining.remove(matched);
        }
        Collections.shuffle(remaining);
        reordered.addAll(remaining);
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < reordered.size(); index++) {
            GroupReplyPlanItem item = reordered.get(index);
            int order = index + 1;
            item.setItemOrder(order).setUpdatedAt(now);
            itemMapper.update(null,
                    new LambdaUpdateWrapper<GroupReplyPlanItem>()
                            .eq(GroupReplyPlanItem::getId, item.getId())
                            .eq(GroupReplyPlanItem::getPlanId,
                                    plan.getId())
                            .set(GroupReplyPlanItem::getItemOrder, order)
                            .set(GroupReplyPlanItem::getParticipantStatus,
                                    item.getParticipantStatus())
                            .set(GroupReplyPlanItem::getUpdatedAt, now));
        }
        plan.setUpdatedAt(now);
        planMapper.update(null,
                new LambdaUpdateWrapper<GroupReplyPlan>()
                        .eq(GroupReplyPlan::getId, plan.getId())
                        .set(GroupReplyPlan::getUpdatedAt, now));
        return new GroupReplyPlanSelection(
                plan.getSource(), plan.getContextId(),
                plan.getExecutionKey(), plan.getDisplayName(),
                List.copyOf(reordered));
    }

    /** Caller must hold the conversation lock. */
    public void clearConversationPlans(GroupConversation conversation) {
        List<Long> planIds = planMapper.selectList(new LambdaQueryWrapper<GroupReplyPlan>()
                        .select(GroupReplyPlan::getId)
                        .eq(GroupReplyPlan::getConversationId, conversation.getId()))
                .stream().map(GroupReplyPlan::getId).toList();
        if (!planIds.isEmpty()) {
            runtimeChildSceneMapper.delete(
                    new LambdaQueryWrapper<TrpgRuntimeChildScene>()
                            .in(TrpgRuntimeChildScene::getPlanId,
                                    planIds));
            itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                    .in(GroupReplyPlanItem::getPlanId, planIds));
            planMapper.delete(new LambdaQueryWrapper<GroupReplyPlan>()
                    .in(GroupReplyPlan::getId, planIds));
        }
        conversation.setActiveReplyPlanId(null);
        updateActivePlan(conversation, null, conversation.getUpdatedAt());
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
            plan = active
                    .setExecutionKey(request.getExecutionKey().trim())
                    .setDisplayName(request.getDisplayName().trim())
                    .setUpdatedAt(now);
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
                    .setExecutionKey(request.getExecutionKey().trim())
                    .setDisplayName(request.getDisplayName().trim())
                    .setResumePlanId(startsCombat && active != null ? active.getId() : null)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            planMapper.insert(plan);
            conversation.setActiveReplyPlanId(plan.getId()).setUpdatedAt(now);
            conversationMapper.updateById(conversation);
        }
        insertItems(plan.getId(), request.getItems(), now);
        return toVO(plan);
    }

    private GroupReplyPlanVO finishLocked(GroupConversation conversation, GroupReplyPlan active) {
        GroupReplyPlan resumePlan = null;
        GroupReplyPlan nextPlan = null;
        if ((GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                active.getSource())
                || GroupChatConstant.PLAN_SOURCE_POST_COMBAT.equals(
                        active.getSource()))
                && active.getResumePlanId() != null) {
            resumePlan = planMapper.selectById(
                    active.getResumePlanId());
            if (resumePlan == null
                    || !java.util.Objects.equals(
                            resumePlan.getConversationId(),
                            conversation.getId())
                    || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                            resumePlan.getSource())) {
                throw new UserRequestException(
                        "战斗恢复回复计划数据不完整");
            }
        }
        if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                active.getSource())
                && active.getNextPlanId() != null) {
            nextPlan = planMapper.selectById(
                    active.getNextPlanId());
            if (nextPlan == null
                    || !java.util.Objects.equals(
                            nextPlan.getConversationId(),
                            conversation.getId())
                    || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                            nextPlan.getSource())) {
                throw new UserRequestException(
                        "下一场景回复计划数据不完整");
            }
        }
        itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, active.getId()));
        planMapper.deleteById(active.getId());
        if (GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource())
                || GroupChatConstant.PLAN_SOURCE_POST_COMBAT.equals(
                        active.getSource())) {
            Long resumePlanId = resumePlan == null
                    ? null : resumePlan.getId();
            conversation.setActiveReplyPlanId(resumePlanId).setUpdatedAt(LocalDateTime.now());
            updateActivePlan(conversation, resumePlanId,
                    conversation.getUpdatedAt());
            return resumePlan == null ? null : toVO(resumePlan);
        }
        if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(active.getSource())) {
            conversation.setActiveReplyPlanId(nextPlan == null ? null : nextPlan.getId())
                    .setUpdatedAt(LocalDateTime.now());
            updateActivePlan(conversation,
                    conversation.getActiveReplyPlanId(),
                    conversation.getUpdatedAt());
            return nextPlan == null ? null : toVO(nextPlan);
        }
        return toVO(createDefaultPlan(conversation));
    }

    private void updateActivePlan(
            GroupConversation conversation, Long activePlanId,
            LocalDateTime updatedAt) {
        LambdaUpdateWrapper<GroupConversation> update =
                new LambdaUpdateWrapper<GroupConversation>()
                        .eq(GroupConversation::getId, conversation.getId())
                        .set(GroupConversation::getActiveReplyPlanId,
                                activePlanId);
        if (updatedAt != null) {
            update.set(GroupConversation::getUpdatedAt, updatedAt);
        }
        conversationMapper.update(null, update);
    }

    private void insertItems(
            Long planId, List<GroupReplyPlanDTO.Item> items,
            LocalDateTime now) {
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            GroupReplyPlanDTO.Item item = items.get(itemIndex);
            String actorType = StringUtils.hasText(item.getActorType())
                    ? item.getActorType().trim().toLowerCase(Locale.ROOT)
                    : GroupChatConstant.ACTOR_CHARACTER;
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(planId)
                    .setItemOrder(item.getOrder() == null
                            ? itemIndex + 1 : item.getOrder())
                    .setActorType(actorType)
                    .setActorId(item.getActorId())
                    .setSubjectCharacterId(item.getSubjectCharacterId())
                    .setSubjectCharacterName(
                            item.getSubjectCharacterName())
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
    }

    private void insertCombatItems(
            Long planId,
            int round,
            List<CombatPlanItem> participants,
            LocalDateTime now) {
        for (int index = 0; index < participants.size(); index++) {
            CombatPlanItem participant = participants.get(index);
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(planId)
                    .setItemOrder(participant.order() == null
                            ? index + 1 : participant.order())
                    .setActorType(participant.actorType())
                    .setActorId(participant.actorId())
                    .setSubjectCharacterId(
                            participant.subjectCharacterId())
                    .setSubjectCharacterName(
                            participant.subjectCharacterName())
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
    }

    private GroupReplyPlan createDefaultPlan(GroupConversation conversation) {
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan plan = new GroupReplyPlan()
                .setConversationId(conversation.getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_USER)
                .setExecutionKey("default")
                .setDisplayName("群聊")
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
                    .setItemOrder(order++)
                    .setActorType(member.getActorType())
                    .setActorId(member.getActorId())
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
        List<GroupReplyPlanVO.Item> voItems = items.stream()
                .map(item -> new GroupReplyPlanVO.Item(
                        item.getId(), item.getItemOrder(),
                        item.getActorType(), item.getActorId(),
                        item.getSubjectCharacterId(),
                        item.getSubjectCharacterName(),
                        item.getParticipantStatus()))
                .toList();
        return new GroupReplyPlanVO(
                plan.getId(), plan.getSource(), plan.getContextId(),
                plan.getExecutionKey(), plan.getDisplayName(),
                plan.getNextPlanId(), plan.getResumePlanId(),
                plan.getParentPlanId(), voItems);
    }

    private List<GroupReplyPlanItem> orderedItems(Long planId) {
        return itemMapper.selectList(new LambdaQueryWrapper<GroupReplyPlanItem>()
                .eq(GroupReplyPlanItem::getPlanId, planId)
                .orderByAsc(GroupReplyPlanItem::getItemOrder)
                .orderByAsc(GroupReplyPlanItem::getId));
    }

    private boolean sameSceneActor(
            GroupReplyPlanItem item, GroupActionSpec action) {
        return java.util.Objects.equals(
                item.getActorType(), action.actorType())
                && java.util.Objects.equals(
                item.getActorId(), action.actorId())
                && java.util.Objects.equals(
                item.getSubjectCharacterId(),
                action.subjectCharacterId());
    }

    private boolean isInvestigator(GroupReplyPlanItem item) {
        return GroupChatConstant.ACTOR_USER.equals(item.getActorType())
                || GroupChatConstant.ACTOR_CHARACTER.equals(
                        item.getActorType());
    }

    void validateStructure(GroupConversation conversation, GroupReplyPlanDTO request) {
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
        if (!StringUtils.hasText(request.getExecutionKey())
                || !StringUtils.hasText(request.getDisplayName())) {
            throw new UserRequestException("回复计划执行标识和显示名称不能为空");
        }
        if (CollectionUtils.isEmpty(request.getItems())) {
            throw new UserRequestException("回复计划执行项不能为空");
        }
        if (request.getItems().size() > GroupChatConstant.MAX_REPLY_STEPS) {
            throw new UserRequestException("回复计划执行项数量不能超过"
                    + GroupChatConstant.MAX_REPLY_STEPS);
        }
        Set<String> actors = new HashSet<>();
        for (GroupReplyPlanDTO.Item item : request.getItems()) {
            if (item == null) {
                throw new UserRequestException("回复人物不能为空");
            }
            String actorType = StringUtils.hasText(item.getActorType())
                    ? item.getActorType().trim().toLowerCase(Locale.ROOT)
                    : GroupChatConstant.ACTOR_CHARACTER;
            boolean concreteTrpgCharacter =
                    GroupChatConstant.MODE_TRPG.equals(
                            conversation.getMode())
                    && !GroupChatConstant.ACTOR_KP.equals(actorType);
            boolean combatCharacter =
                    GroupChatConstant.PLAN_SOURCE_COMBAT.equals(source);
            if ((concreteTrpgCharacter || combatCharacter)
                    && item.getSubjectCharacterId() == null) {
                throw new UserRequestException(
                        "TRPG回复人物必须绑定人物卡");
            }
            if ((concreteTrpgCharacter || combatCharacter)
                    && !StringUtils.hasText(
                    item.getSubjectCharacterName())) {
                throw new UserRequestException(
                        "TRPG回复人物必须包含人物卡名称");
            }
            String actorKey = actorType + "\n" + item.getActorId()
                    + (GroupChatConstant.MODE_TRPG.equals(
                    conversation.getMode())
                    ? "\n" + item.getSubjectCharacterId() : "");
            if (!actors.add(actorKey)) {
                throw new UserRequestException(
                        "回复计划中不能重复安排同一人物");
            }
            if (GroupChatConstant.ACTOR_KP.equals(actorType)) {
                if (!GroupChatConstant.MODE_TRPG.equals(
                        conversation.getMode())) {
                    throw new UserRequestException("KP只能用于TRPG群聊");
                }
                if (item.getActorId() != null) {
                    throw new UserRequestException("KP的actorId必须为空");
                }
                if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(source)
                        && (item.getSubjectCharacterId() != null
                        || StringUtils.hasText(
                        item.getSubjectCharacterName()))) {
                    throw new UserRequestException(
                            "探索场景KP不能绑定单一人物卡或名称");
                }
            } else if (GroupChatConstant.ACTOR_CHARACTER.equals(actorType)) {
                if (item.getActorId() == null) {
                    throw new UserRequestException("回复人物id不能为空");
                }
                conversationService.checkReplyMember(
                        conversation.getId(), actorType,
                        item.getActorId(), false);
            } else if (GroupChatConstant.ACTOR_USER.equals(actorType)) {
                if (!GroupChatConstant.MODE_TRPG.equals(
                        conversation.getMode())
                        || item.getActorId() == null) {
                    throw new UserRequestException(
                            "用户调查员只能用于TRPG且actorId不能为空");
                }
                boolean exists = participantService
                        .listInvestigators(conversation)
                        .stream()
                        .anyMatch(participant ->
                                GroupChatConstant.ACTOR_USER.equals(
                                        participant.actor().type())
                                        && java.util.Objects.equals(
                                        participant.actor().id(),
                                        item.getActorId()));
                if (!exists) {
                    throw new UserRequestException(
                            "用户调查员人物卡不存在");
                }
            } else {
                throw new UserRequestException(
                        "回复人物类型仅支持user、character或kp");
            }
        }
    }

    private String combatExecutionKey(int round) {
        return "combat:round:" + round;
    }

    private String combatDisplayName(int round) {
        return "战斗第" + round + "轮";
    }

    private GroupConversationLockService.OwnedLock requireLock(Long conversationId) {
        GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
        if (lock == null) {
            throw new UserRequestException("当前群聊正在生成回复，请稍后再试");
        }
        return lock;
    }

    private void rejectPublicCombatSource(GroupReplyPlanDTO request) {
        if (request != null && StringUtils.hasText(request.getSource())
                && GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                request.getSource().trim().toUpperCase(Locale.ROOT))) {
            throw new UserRequestException("战斗只能由 KP 在场景中发起，不能通过回复计划接口创建");
        }
    }

    private void rejectPublicTrpgMutation(
            GroupConversation conversation) {
        if (conversation != null
                && GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException(
                    "TRPG回复计划只能通过跑团生命周期变更");
        }
    }

    private void rejectPublicCombatPlan(GroupReplyPlan active) {
        if (active != null && GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource())) {
            throw new UserRequestException("战斗计划只能通过战斗流程推进或结束");
        }
    }

    public record CombatPlanItem(
            String actorType,
            Long actorId,
            Long subjectCharacterId,
            String subjectCharacterName,
            Integer order) {
    }
}
