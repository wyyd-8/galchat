package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
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
    public String suspendInvestigators(
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
        LocalDateTime now = LocalDateTime.now();
        for (TrpgParticipantService.Participant participant : selected) {
            suspensionMapper.insert(new TrpgInvestigatorSuspension()
                    .setConversationId(conversationId)
                    .setSubjectCharacterId(participant.cardId())
                    .setState(TrpgInvestigatorSuspension.STATE_SUSPENDED)
                    .setSuspensionContext(context)
                    .setOriginContextId(execution.scene().getContextId())
                    .setCreatedAt(now).setUpdatedAt(now));
            progressStore.clearReady(conversationId,
                    execution.scene().getId(), participant.cardId());
        }
        return "已悬置调查员"
                + String.join("、", names(selected))
                + "的剧情线。请在本次公开回复中自然交代停镜位置，"
                + "然后切换镜头至其他调查员。";
    }

    @Transactional(rollbackFor = Exception.class)
    public String resumeSuspendedInvestigators(
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
        for (TrpgParticipantService.Participant participant : selected) {
            TrpgInvestigatorSuspension suspension = existing.get(
                    participant.cardId());
            requireSuspended(suspension, participant.investigatorName());
            ensureSceneItem(execution.scene(), participant, now);
            suspension.setState(
                            TrpgInvestigatorSuspension
                                    .STATE_REENTRY_PENDING)
                    .setReentryContext(context)
                    .setRecoverySceneName(null)
                    .setRecoveryPlanId(execution.scene().getId())
                    .setUpdatedAt(now);
            suspensionMapper.updateById(suspension);
        }
        return "调查员" + String.join("、", names(selected))
                + "已并入当前场景。请在本次回复中叙述其重新出现；"
                + "他们将从下一轮开始行动。";
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
                .filter(row -> Objects.equals(
                        turn.getPlanId(), row.getRecoveryPlanId()))
                .filter(row -> TrpgInvestigatorSuspension
                        .STATE_REENTRY_PENDING.equals(row.getState())
                        || TrpgInvestigatorSuspension
                        .STATE_RECOVERY_QUEUED.equals(row.getState()))
                .forEach(row -> suspensionMapper.deleteById(row.getId()));
    }

    private String queueIndependentScene(
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
        return "已将调查员" + String.join("、", names(selected))
                + "的独立恢复场景“" + normalizedSceneName
                + "”排入后续主场景队列。";
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

    private void ensureSceneItem(
            GroupReplyPlan scene,
            TrpgParticipantService.Participant participant,
            LocalDateTime now) {
        boolean exists = orderedItems(scene.getId()).stream()
                .filter(item -> Objects.equals(
                        item.getSubjectCharacterId(), participant.cardId()))
                .findFirst()
                .map(item -> {
                    item.setParticipantStatus(
                                    GroupChatConstant.PARTICIPANT_ACTIVE)
                            .setUpdatedAt(now);
                    itemMapper.updateById(item);
                    return true;
                }).orElse(false);
        if (exists) {
            return;
        }
        int nextOrder = orderedItems(scene.getId()).stream()
                .map(GroupReplyPlanItem::getItemOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
        itemMapper.insert(new GroupReplyPlanItem()
                .setPlanId(scene.getId()).setItemOrder(nextOrder)
                .setActorType(participant.actor().type())
                .setActorId(participant.actor().id())
                .setSubjectCharacterId(participant.cardId())
                .setSubjectCharacterName(participant.investigatorName())
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE)
                .setCreatedAt(now).setUpdatedAt(now));
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
