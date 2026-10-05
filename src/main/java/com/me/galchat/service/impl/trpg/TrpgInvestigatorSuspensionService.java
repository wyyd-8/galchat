package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpInvestigatorSuspensionDTOs;
import com.me.galchat.utils.RedisAfterCommitCleanup;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgInvestigatorSuspension;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgInvestigatorSuspensionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgInvestigatorSuspensionService {

    public static final String RESUME_CURRENT_SCENE = "CURRENT_SCENE";
    public static final String RESUME_INDEPENDENT_SCENE =
            "INDEPENDENT_SCENE";

    private final GroupConversationService conversationService;
    private final TrpgParticipantService participantService;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final GroupConversationMapper conversationMapper;
    private final TrpgInvestigatorSuspensionMapper suspensionMapper;
    private final TrpgSceneProgressStore progressStore;

    @Transactional(rollbackFor = Exception.class)
    public KpInvestigatorSuspensionDTOs.SuspendResult suspendInvestigators(
            Long conversationId,
            Long replyStepId,
            List<String> investigatorNames,
            String suspensionContext) {
        NarrativeExecution execution = requireNarrativeExecution(
                conversationId, replyStepId);
        List<TrpgParticipantService.Participant> selected =
                selectParticipants(execution.conversation(),
                        investigatorNames);
        String context = normalizeContext(
                suspensionContext, "悬置情境");
        Map<Long, TrpgInvestigatorSuspension> existing =
                suspensions(conversationId);
        for (TrpgParticipantService.Participant participant : selected) {
            if (existing.containsKey(participant.cardId())) {
                throw new UserRequestException(
                        "调查员剧情已经被悬置："
                                + participant.investigatorName());
            }
        }
        int total = participantService.listInvestigators(
                execution.conversation()).size();
        long alreadyUnavailable = existing.values().stream()
                .filter(row -> !TrpgInvestigatorSuspension
                        .STATE_REENTRY_PENDING.equals(row.getState()))
                .count();
        if (total - alreadyUnavailable - selected.size() < 1) {
            throw new UserRequestException(
                    "切换镜头后至少保留一名未悬置调查员");
        }
        Set<Long> readyBefore = progressStore.readyCharacterIds(
                conversationId, execution.scene().getId());
        List<KpInvestigatorSuspensionDTOs.SuspensionUndo> undo = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (TrpgParticipantService.Participant participant : selected) {
            TrpgInvestigatorSuspension suspension = new TrpgInvestigatorSuspension()
                    .setConversationId(conversationId)
                    .setSubjectCharacterId(participant.cardId())
                    .setState(TrpgInvestigatorSuspension.STATE_SUSPENDED)
                    .setSuspensionContext(context)
                    .setOriginContextId(execution.scene().getContextId())
                    .setCreatedAt(now).setUpdatedAt(now);
            if (suspensionMapper.insert(suspension) != 1 || suspension.getId() == null) {
                throw new IllegalStateException("调查员悬置状态保存失败");
            }
            undo.add(new KpInvestigatorSuspensionDTOs.SuspensionUndo(
                    suspension.getId(), participant.cardId(), readyBefore.contains(participant.cardId())));
            progressStore.clearReady(conversationId,
                    execution.scene().getId(), participant.cardId());
        }
        String message = "已悬置调查员"
                + String.join("、", names(selected))
                + "的剧情线。请在本次公开回复中自然交代停镜位置，"
                + "然后切换镜头至其他调查员。";
        return new KpInvestigatorSuspensionDTOs.SuspendResult(
                message, execution.scene().getId(), List.copyOf(undo));
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackSuspension(Long conversationId,
            KpInvestigatorSuspensionDTOs.SuspendResult result) {
        if (result == null || result.scenePlanId() == null
                || result.undo() == null || result.undo().isEmpty()) {
            throw new IllegalStateException("悬置记录缺少撤销数据，无法回滚");
        }
        GroupReplyPlan scene = planMapper.selectById(result.scenePlanId());
        if (scene == null || !Objects.equals(conversationId, scene.getConversationId())) {
            throw new IllegalStateException("悬置记录的场景已变化，无法回滚");
        }
        for (var undo : result.undo()) {
            if (undo == null || undo.suspensionId() == null || undo.characterId() == null) {
                throw new IllegalStateException("悬置记录缺少撤销数据，无法回滚");
            }
            TrpgInvestigatorSuspension current = suspensionMapper.selectById(undo.suspensionId());
            if (current == null || !Objects.equals(conversationId, current.getConversationId())
                    || !Objects.equals(undo.characterId(), current.getSubjectCharacterId())
                    || !TrpgInvestigatorSuspension.STATE_SUSPENDED.equals(current.getState())) {
                throw new IllegalStateException("调查员悬置状态已发生后续变化，无法安全回滚");
            }
        }
        for (var undo : result.undo()) {
            int deleted = suspensionMapper.delete(new LambdaQueryWrapper<TrpgInvestigatorSuspension>()
                    .eq(TrpgInvestigatorSuspension::getId, undo.suspensionId())
                    .eq(TrpgInvestigatorSuspension::getConversationId, conversationId)
                    .eq(TrpgInvestigatorSuspension::getSubjectCharacterId, undo.characterId())
                    .eq(TrpgInvestigatorSuspension::getState, TrpgInvestigatorSuspension.STATE_SUSPENDED));
            if (deleted != 1) {
                throw new IllegalStateException("调查员悬置状态回滚失败");
            }
            if (undo.readyBefore()) {
                RedisAfterCommitCleanup.run("恢复调查员结束探索标记", () ->
                        progressStore.markReady(conversationId, result.scenePlanId(), undo.characterId()));
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public KpInvestigatorSuspensionDTOs.ResumeResult resumeSuspendedInvestigators(
            Long conversationId,
            Long replyStepId,
            List<String> investigatorNames,
            String resumeMode,
            String reentryContext,
            String sceneName) {
        NarrativeExecution execution = requireNarrativeExecution(
                conversationId, replyStepId);
        List<TrpgParticipantService.Participant> selected =
                selectParticipants(execution.conversation(),
                        investigatorNames);
        String context = normalizeContext(reentryContext, "重新入场情境");
        String mode = StringUtils.hasText(resumeMode)
                ? resumeMode.trim().toUpperCase() : "";
        if (!RESUME_CURRENT_SCENE.equals(mode)
                && !RESUME_INDEPENDENT_SCENE.equals(mode)) {
            throw new UserRequestException(
                    "恢复方式仅支持CURRENT_SCENE或INDEPENDENT_SCENE");
        }
        if (RESUME_INDEPENDENT_SCENE.equals(mode)) {
            return queueIndependentScene(
                    execution, selected, context, sceneName);
        }
        Map<Long, TrpgInvestigatorSuspension> existing =
                suspensions(conversationId);
        LocalDateTime now = LocalDateTime.now();
        List<KpInvestigatorSuspensionDTOs.ResumeUndo> undo = new ArrayList<>();
        for (TrpgParticipantService.Participant participant : selected) {
            TrpgInvestigatorSuspension suspension = existing.get(
                    participant.cardId());
            requireSuspended(suspension, participant.investigatorName());
            TrpgInvestigatorSuspension before = copySuspension(suspension);
            GroupReplyPlanItem oldItem = orderedItems(execution.scene().getId()).stream()
                    .filter(item -> Objects.equals(item.getSubjectCharacterId(), participant.cardId()))
                    .findFirst().orElse(null);
            String oldStatus = oldItem == null ? null : oldItem.getParticipantStatus();
            LocalDateTime oldUpdated = oldItem == null ? null : oldItem.getUpdatedAt();
            GroupReplyPlanItem item = ensureSceneItem(execution.scene(), participant, now);
            undo.add(new KpInvestigatorSuspensionDTOs.ResumeUndo(before, item.getId(),
                    oldItem == null, oldStatus, oldUpdated));
            suspension.setState(
                            TrpgInvestigatorSuspension
                                    .STATE_REENTRY_PENDING)
                    .setReentryContext(context)
                    .setRecoverySceneName(null)
                    .setRecoveryPlanId(execution.scene().getId())
                    .setUpdatedAt(now);
            suspensionMapper.updateById(suspension);
        }
        return new KpInvestigatorSuspensionDTOs.ResumeResult(
                "调查员" + String.join("、", names(selected))
                + "已并入当前场景。请在本次回复中叙述其重新出现；他们将从下一轮开始行动。",
                execution.scene().getId(), null, null, List.copyOf(undo));
    }

    public boolean isUnavailable(
            Long conversationId, Long characterId, Long planId) {
        TrpgInvestigatorSuspension suspension = find(
                conversationId, characterId);
        if (suspension == null
                || TrpgInvestigatorSuspension.STATE_REENTRY_PENDING.equals(
                        suspension.getState())) {
            return false;
        }
        return !(TrpgInvestigatorSuspension.STATE_RECOVERY_QUEUED.equals(
                suspension.getState())
                && Objects.equals(planId, suspension.getRecoveryPlanId()));
    }

    public boolean hasSuspendedInvestigators(Long conversationId) {
        return suspensions(conversationId).values().stream()
                .anyMatch(row -> TrpgInvestigatorSuspension
                        .STATE_SUSPENDED.equals(row.getState()));
    }

    public String reentryPrompt(
            Long conversationId, Long characterId, Long planId) {
        TrpgInvestigatorSuspension suspension = find(
                conversationId, characterId);
        if (suspension == null || isUnavailable(
                conversationId, characterId, planId)
                || !StringUtils.hasText(suspension.getReentryContext())) {
            return "";
        }
        return """
                <investigator-storyline-reentry>
                停镜前状态：%s
                停镜期间实际经历与重新入场位置：%s
                系统没有主持该调查员在停镜期间的其他行动；不得自行补写未给出的经历。
                现在从上述重新入场位置继续作出本轮行动。
                </investigator-storyline-reentry>
                """.formatted(
                suspension.getSuspensionContext(),
                suspension.getReentryContext());
    }

    public String sceneReentryPrompt(Long conversationId, Long planId) {
        List<TrpgInvestigatorSuspension> rows = suspensions(conversationId)
                .values().stream()
                .filter(row -> Objects.equals(
                        planId, row.getRecoveryPlanId()))
                .filter(row -> StringUtils.hasText(
                        row.getReentryContext()))
                .toList();
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(
                "\n<kp-storyline-reentry>\n");
        for (TrpgInvestigatorSuspension row : rows) {
            result.append("停镜前状态：")
                    .append(row.getSuspensionContext())
                    .append("\n停镜期间实际经历与重新入场位置：")
                    .append(row.getReentryContext()).append('\n');
        }
        return result.append(
                "只叙述以上明确提供的经历，不补写停镜期间的个人行动。\n"
                        + "</kp-storyline-reentry>\n").toString();
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeReentriesAfterTurn(GroupChatTurn turn) {
        if (turn == null || turn.getId() == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                        turn.getPlanSource())) {
            return;
        }
        Map<Long, TrpgInvestigatorSuspension> rows = suspensions(
                turn.getConversationId());
        if (rows.isEmpty()) {
            return;
        }
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .eq(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .in(GroupChatReplyStep::getSpeakerType,
                                GroupChatConstant.ACTOR_USER,
                                GroupChatConstant.ACTOR_CHARACTER));
        if (steps == null) {
            return;
        }
        steps.stream()
                .map(GroupChatReplyStep::getSubjectCharacterId)
                .filter(Objects::nonNull)
                .distinct()
                .map(rows::get)
                .filter(Objects::nonNull)
                .filter(row -> TrpgInvestigatorSuspension.STATE_REENTRY_PENDING.equals(row.getState())
                        || Objects.equals(turn.getPlanId(), row.getRecoveryPlanId()))
                .filter(row -> TrpgInvestigatorSuspension
                        .STATE_REENTRY_PENDING.equals(row.getState())
                        || TrpgInvestigatorSuspension
                        .STATE_RECOVERY_QUEUED.equals(row.getState()))
                .forEach(row -> suspensionMapper.deleteById(row.getId()));
    }

    private KpInvestigatorSuspensionDTOs.ResumeResult queueIndependentScene(
            NarrativeExecution execution,
            List<TrpgParticipantService.Participant> selected,
            String reentryContext,
            String sceneName) {
        String normalizedSceneName = normalizeSceneName(sceneName);
        Map<Long, TrpgInvestigatorSuspension> existing = suspensions(
                execution.conversation().getId());
        List<TrpgInvestigatorSuspension> selectedSuspensions =
                new ArrayList<>();
        Long originContextId = null;
        for (TrpgParticipantService.Participant participant : selected) {
            TrpgInvestigatorSuspension suspension = existing.get(
                    participant.cardId());
            requireSuspended(suspension, participant.investigatorName());
            if (originContextId == null) {
                originContextId = suspension.getOriginContextId();
            } else if (!Objects.equals(
                    originContextId, suspension.getOriginContextId())) {
                throw new UserRequestException(
                        "同一独立恢复场景中的调查员必须来自相同模组场景");
            }
            selectedSuspensions.add(suspension);
        }
        GroupReplyPlan tail = mainSceneTail(execution.scene());
        LocalDateTime tailUpdatedBefore = tail.getUpdatedAt();
        List<KpInvestigatorSuspensionDTOs.ResumeUndo> undo = selectedSuspensions.stream()
                .map(row -> new KpInvestigatorSuspensionDTOs.ResumeUndo(copySuspension(row), null, false, null, null))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan recovery = new GroupReplyPlan()
                .setConversationId(execution.conversation().getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(originContextId)
                .setExecutionKey("recovery:pending")
                .setDisplayName(normalizedSceneName)
                .setCreatedAt(now).setUpdatedAt(now);
        planMapper.insert(recovery);
        recovery.setExecutionKey("scene:" + recovery.getId());
        planMapper.updateById(recovery);
        int order = 1;
        for (TrpgParticipantService.Participant participant : selected) {
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(recovery.getId()).setItemOrder(order++)
                    .setActorType(participant.actor().type())
                    .setActorId(participant.actor().id())
                    .setSubjectCharacterId(participant.cardId())
                    .setSubjectCharacterName(
                            participant.investigatorName())
                    .setParticipantStatus(
                            GroupChatConstant.PARTICIPANT_ACTIVE)
                    .setCreatedAt(now).setUpdatedAt(now));
        }
        itemMapper.insert(new GroupReplyPlanItem()
                .setPlanId(recovery.getId()).setItemOrder(order)
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE)
                .setCreatedAt(now).setUpdatedAt(now));
        tail.setNextPlanId(recovery.getId()).setUpdatedAt(now);
        planMapper.updateById(tail);
        for (TrpgInvestigatorSuspension suspension :
                selectedSuspensions) {
            suspension.setState(TrpgInvestigatorSuspension
                            .STATE_RECOVERY_QUEUED)
                    .setReentryContext(reentryContext)
                    .setRecoverySceneName(normalizedSceneName)
                    .setRecoveryPlanId(recovery.getId())
                    .setUpdatedAt(now);
            suspensionMapper.updateById(suspension);
        }
        return new KpInvestigatorSuspensionDTOs.ResumeResult(
                "已将调查员" + String.join("、", names(selected))
                + "的独立恢复场景“" + normalizedSceneName + "”排入后续主场景队列。",
                recovery.getId(), tail.getId(), tailUpdatedBefore, undo);
    }

    private GroupReplyPlan mainSceneTail(GroupReplyPlan scene) {
        GroupReplyPlan root = scene;
        Set<Long> visited = new LinkedHashSet<>();
        while (root.getParentPlanId() != null) {
            if (!visited.add(root.getId())) {
                throw new UserRequestException("场景计划父链存在循环");
            }
            root = planMapper.selectById(root.getParentPlanId());
            if (root == null) {
                throw new UserRequestException("场景计划父链不完整");
            }
        }
        GroupReplyPlan tail = root;
        visited.clear();
        while (tail.getNextPlanId() != null) {
            if (!visited.add(tail.getId())) {
                throw new UserRequestException("主场景队列存在循环");
            }
            GroupReplyPlan next = planMapper.selectById(
                    tail.getNextPlanId());
            if (next == null
                    || !Objects.equals(next.getConversationId(),
                            scene.getConversationId())
                    || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                            next.getSource())
                    || next.getParentPlanId() != null) {
                throw new UserRequestException("后续主场景计划不完整");
            }
            tail = next;
        }
        return tail;
    }

    private String normalizeSceneName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException("独立恢复场景名称不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new UserRequestException(
                    "独立恢复场景名称不能超过200个字符");
        }
        return normalized;
    }

    private GroupReplyPlanItem ensureSceneItem(
            GroupReplyPlan scene, TrpgParticipantService.Participant participant, LocalDateTime now) {
        List<GroupReplyPlanItem> items = orderedItems(scene.getId());
        GroupReplyPlanItem existing = items.stream()
                .filter(item -> Objects.equals(item.getSubjectCharacterId(), participant.cardId()))
                .findFirst().orElse(null);
        if (existing != null) {
            existing.setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE).setUpdatedAt(now);
            if (itemMapper.updateById(existing) != 1) throw new IllegalStateException("恢复场景成员失败");
            return existing;
        }
        int nextOrder = items.stream().map(GroupReplyPlanItem::getItemOrder)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        GroupReplyPlanItem item = new GroupReplyPlanItem()
                .setPlanId(scene.getId()).setItemOrder(nextOrder)
                .setActorType(participant.actor().type()).setActorId(participant.actor().id())
                .setSubjectCharacterId(participant.cardId()).setSubjectCharacterName(participant.investigatorName())
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE).setCreatedAt(now).setUpdatedAt(now);
        if (itemMapper.insert(item) != 1 || item.getId() == null) throw new IllegalStateException("恢复场景成员失败");
        return item;
    }

    private TrpgInvestigatorSuspension copySuspension(TrpgInvestigatorSuspension source) {
        var copy = new TrpgInvestigatorSuspension();
        org.springframework.beans.BeanUtils.copyProperties(source, copy);
        return copy;
    }

    /** A finished run has no future reentry action, including in queued scenes. */
    @Transactional(rollbackFor = Exception.class)
    public void completeReentriesForRun(Long conversationId) {
        suspensions(conversationId).values().stream()
                .filter(row -> TrpgInvestigatorSuspension.STATE_REENTRY_PENDING.equals(row.getState())
                        || TrpgInvestigatorSuspension.STATE_RECOVERY_QUEUED.equals(row.getState()))
                .forEach(row -> {
                    if (suspensionMapper.deleteById(row.getId()) != 1) {
                        throw new IllegalStateException("清理跑团待归队状态失败");
                    }
                });
    }

    /** A completed scene cannot supply a future first reentry action. */
    @Transactional(rollbackFor = Exception.class)
    public void completeReentriesForScene(Long conversationId, Long scenePlanId) {
        suspensions(conversationId).values().stream()
                .filter(row -> Objects.equals(scenePlanId, row.getRecoveryPlanId()))
                .filter(row -> TrpgInvestigatorSuspension.STATE_REENTRY_PENDING.equals(row.getState())
                        || TrpgInvestigatorSuspension.STATE_RECOVERY_QUEUED.equals(row.getState()))
                .forEach(row -> suspensionMapper.deleteById(row.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackResume(Long conversationId, KpInvestigatorSuspensionDTOs.ResumeResult result) {
        if (result == null || result.scenePlanId() == null || result.undo() == null || result.undo().isEmpty()) {
            throw new IllegalStateException("恢复调查员记录缺少撤销数据，无法回滚");
        }
        GroupReplyPlan scene = planMapper.selectById(result.scenePlanId());
        boolean independent = result.tailPlanId() != null;
        if (scene == null || !Objects.equals(scene.getConversationId(), conversationId)) {
            throw new IllegalStateException("恢复调查员的场景已变化，无法回滚");
        }
        if (independent) {
            GroupReplyPlan tail = planMapper.selectById(result.tailPlanId());
            if (tail == null || !Objects.equals(tail.getConversationId(), conversationId)
                    || !Objects.equals(tail.getNextPlanId(), scene.getId()) || scene.getNextPlanId() != null
                    || Objects.equals(conversationService.requireActive(conversationId).getActiveReplyPlanId(), scene.getId())) {
                throw new IllegalStateException("独立恢复场景已发生后续变化，无法安全回滚");
            }
        }
        String expectedState = independent ? TrpgInvestigatorSuspension.STATE_RECOVERY_QUEUED
                : TrpgInvestigatorSuspension.STATE_REENTRY_PENDING;
        for (var undo : result.undo()) {
            var before = undo == null ? null : undo.before();
            if (before == null || before.getId() == null || !Objects.equals(before.getConversationId(), conversationId)
                    || !TrpgInvestigatorSuspension.STATE_SUSPENDED.equals(before.getState())) {
                throw new IllegalStateException("恢复调查员记录缺少撤销数据，无法回滚");
            }
            var current = suspensionMapper.selectById(before.getId());
            if (current == null || !Objects.equals(current.getConversationId(), conversationId)
                    || !Objects.equals(current.getSubjectCharacterId(), before.getSubjectCharacterId())
                    || !expectedState.equals(current.getState())
                    || !Objects.equals(current.getRecoveryPlanId(), scene.getId())) {
                throw new IllegalStateException("调查员恢复状态已发生后续变化，无法安全回滚");
            }
            if (!independent) {
                var item = undo.sceneItemId() == null ? null : itemMapper.selectById(undo.sceneItemId());
                if (item == null || !Objects.equals(item.getPlanId(), scene.getId())
                        || !Objects.equals(item.getSubjectCharacterId(), before.getSubjectCharacterId())
                        || !GroupChatConstant.PARTICIPANT_ACTIVE.equals(item.getParticipantStatus())) {
                    throw new IllegalStateException("恢复场景成员已变化，无法安全回滚");
                }
            }
        }
        for (var undo : result.undo()) {
            var before = undo.before();
            requireRestored(suspensionMapper.update(null, new LambdaUpdateWrapper<TrpgInvestigatorSuspension>()
                    .eq(TrpgInvestigatorSuspension::getId, before.getId())
                    .eq(TrpgInvestigatorSuspension::getConversationId, conversationId)
                    .eq(TrpgInvestigatorSuspension::getState, expectedState)
                    .set(TrpgInvestigatorSuspension::getState, before.getState())
                    .set(TrpgInvestigatorSuspension::getReentryContext, before.getReentryContext())
                    .set(TrpgInvestigatorSuspension::getRecoverySceneName, before.getRecoverySceneName())
                    .set(TrpgInvestigatorSuspension::getRecoveryPlanId, before.getRecoveryPlanId())
                    .set(TrpgInvestigatorSuspension::getUpdatedAt, before.getUpdatedAt())));
            if (!independent) {
                if (undo.itemCreated()) requireRestored(itemMapper.deleteById(undo.sceneItemId()));
                else requireRestored(itemMapper.update(null, new LambdaUpdateWrapper<GroupReplyPlanItem>()
                        .eq(GroupReplyPlanItem::getId, undo.sceneItemId())
                        .set(GroupReplyPlanItem::getParticipantStatus, undo.participantStatusBefore())
                        .set(GroupReplyPlanItem::getUpdatedAt, undo.itemUpdatedBefore())));
            }
        }
        if (independent) {
            requireRestored(planMapper.update(null, new LambdaUpdateWrapper<GroupReplyPlan>()
                    .eq(GroupReplyPlan::getId, result.tailPlanId()).eq(GroupReplyPlan::getNextPlanId, scene.getId())
                    .set(GroupReplyPlan::getNextPlanId, null).set(GroupReplyPlan::getUpdatedAt, result.tailUpdatedBefore())));
            itemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>().eq(GroupReplyPlanItem::getPlanId, scene.getId()));
            requireRestored(planMapper.deleteById(scene.getId()));
        }
    }

    private void requireRestored(int affected) {
        if (affected != 1) throw new IllegalStateException("调查员恢复状态回滚失败");
    }

    private NarrativeExecution requireNarrativeExecution(
            Long conversationId, Long replyStepId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        GroupChatReplyStep step = stepMapper.selectById(replyStepId);
        GroupChatTurn turn = step == null
                ? null : turnMapper.selectById(step.getTurnId());
        if (step == null || turn == null
                || !Objects.equals(conversationId,
                        turn.getConversationId())) {
            throw new UserRequestException("当前叙事步骤不存在");
        }
        if (!GroupChatConstant.ACTOR_KP.equals(step.getSpeakerType())) {
            throw new UserAuthException("只有KP可以管理调查员剧情悬置");
        }
        if (!GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                turn.getPlanSource())
                && !GroupChatConstant.PLAN_SOURCE_POST_COMBAT.equals(
                        turn.getPlanSource())) {
            throw new UserRequestException(
                    "只能在探索或战斗结束后的叙事过渡中悬置调查员");
        }
        GroupReplyPlan active = planMapper.selectById(turn.getPlanId());
        if (active == null || !Objects.equals(
                active.getId(), conversation.getActiveReplyPlanId())) {
            throw new UserRequestException("当前叙事计划已变化");
        }
        GroupReplyPlan scene = active;
        if (GroupChatConstant.PLAN_SOURCE_POST_COMBAT.equals(
                active.getSource())) {
            scene = active.getResumePlanId() == null ? null
                    : planMapper.selectById(active.getResumePlanId());
        }
        if (scene == null || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                scene.getSource())) {
            throw new UserRequestException("当前没有可恢复的探索场景");
        }
        return new NarrativeExecution(conversation, scene);
    }

    private List<TrpgParticipantService.Participant> selectParticipants(
            GroupConversation conversation, List<String> suppliedNames) {
        List<String> names = normalizeNames(suppliedNames);
        Map<String, List<TrpgParticipantService.Participant>> byName =
                new LinkedHashMap<>();
        for (TrpgParticipantService.Participant participant :
                participantService.listInvestigators(conversation)) {
            byName.computeIfAbsent(participant.investigatorName(),
                    ignored -> new ArrayList<>()).add(participant);
        }
        List<TrpgParticipantService.Participant> selected =
                new ArrayList<>();
        for (String name : names) {
            List<TrpgParticipantService.Participant> matches =
                    byName.getOrDefault(name, List.of());
            if (matches.size() != 1) {
                throw new UserRequestException(
                        "调查员名称不存在或重名：" + name);
            }
            selected.add(matches.getFirst());
        }
        return List.copyOf(selected);
    }

    private List<String> normalizeNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new UserRequestException("调查员名单不能为空");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)
                    || !result.add(value.trim())) {
                throw new UserRequestException(
                        "调查员名单不能为空或重复");
            }
        }
        return List.copyOf(result);
    }

    private String normalizeContext(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 4000) {
            throw new UserRequestException(
                    fieldName + "不能超过4000个字符");
        }
        return normalized;
    }

    private List<String> names(
            List<TrpgParticipantService.Participant> participants) {
        return participants.stream()
                .map(TrpgParticipantService.Participant::investigatorName)
                .toList();
    }

    private Map<Long, TrpgInvestigatorSuspension> suspensions(
            Long conversationId) {
        Map<Long, TrpgInvestigatorSuspension> result =
                new LinkedHashMap<>();
        List<TrpgInvestigatorSuspension> rows = suspensionMapper.selectList(
                new LambdaQueryWrapper<TrpgInvestigatorSuspension>()
                        .eq(TrpgInvestigatorSuspension::getConversationId,
                                conversationId)
                        .orderByAsc(TrpgInvestigatorSuspension::getId));
        if (rows != null) {
            rows.forEach(row -> result.put(
                    row.getSubjectCharacterId(), row));
        }
        return result;
    }

    private TrpgInvestigatorSuspension find(
            Long conversationId, Long characterId) {
        return suspensionMapper.selectOne(
                new LambdaQueryWrapper<TrpgInvestigatorSuspension>()
                        .eq(TrpgInvestigatorSuspension::getConversationId,
                                conversationId)
                        .eq(TrpgInvestigatorSuspension::getSubjectCharacterId,
                                characterId)
                        .last("limit 1"));
    }

    private void requireSuspended(
            TrpgInvestigatorSuspension suspension, String name) {
        if (suspension == null
                || !TrpgInvestigatorSuspension.STATE_SUSPENDED.equals(
                        suspension.getState())) {
            throw new UserRequestException(
                    "调查员剧情未处于可恢复的悬置状态：" + name);
        }
    }

    private List<GroupReplyPlanItem> orderedItems(Long planId) {
        List<GroupReplyPlanItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<GroupReplyPlanItem>()
                        .eq(GroupReplyPlanItem::getPlanId, planId)
                        .orderByAsc(GroupReplyPlanItem::getItemOrder)
                        .orderByAsc(GroupReplyPlanItem::getId));
        return items == null ? List.of() : items;
    }

    private record NarrativeExecution(
            GroupConversation conversation,
            GroupReplyPlan scene) {
    }
}
