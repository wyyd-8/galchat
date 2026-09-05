package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgChildSceneCommandService {

    private final GroupConversationService conversationService;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final TrpgChildScenePlanService childPlanService;
    private final TrpgSceneProgressStore progressStore;
    private final GroupChatToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;

    private static final String START_CHILD_SCENE_TOOL =
            "startChildScene";

    @Transactional(rollbackFor = Exception.class)
    public String startChildScene(
            Long conversationId,
            Long replyStepId,
            String childSceneName,
            List<String> investigatorNames) {
        SceneExecution execution = requireKpSceneExecution(
                conversationId, replyStepId);
        requireRootSceneForSwitch(execution.plan());
        String sceneName = normalizeSceneName(childSceneName);
        Selected selected = selectItems(
                execution, investigatorNames,
                GroupChatConstant.PARTICIPANT_ACTIVE);
        List<PreparedChildStart> earlier = recordedStartCalls(
                replyStepId).stream()
                .map(call -> readPreparedStart(
                        execution, call.getToolArguments()))
                .filter(java.util.Objects::nonNull)
                .toList();
        ensureDistinctDestinationAndInvestigators(
                sceneName, selected, earlier);
        return "已创建子场景“" + sceneName
                + "”，调查员"
                + String.join("、", selected.names())
                + "将进入该场景。";
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeStartAfterTurn(
            GroupConversation conversation,
            GroupChatTurn completedTurn) {
        if (conversation == null || completedTurn == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                completedTurn.getPlanSource())) {
            return false;
        }
        List<GroupChatReplyStep> completedKpSteps =
                stepMapper.selectList(
                        new LambdaQueryWrapper<GroupChatReplyStep>()
                                .eq(GroupChatReplyStep::getTurnId,
                                        completedTurn.getId())
                                .eq(GroupChatReplyStep::getSpeakerType,
                                        GroupChatConstant.ACTOR_KP)
                                .eq(GroupChatReplyStep::getStatus,
                                        GroupChatConstant
                                                .STATUS_COMPLETED)
                                .orderByAsc(
                                        GroupChatReplyStep::getStepNo));
        for (GroupChatReplyStep step : completedKpSteps) {
            SceneExecution execution;
            try {
                execution = requireKpSceneExecution(
                        conversation, step, completedTurn);
                requireRootSceneForSwitch(execution.plan());
            } catch (UserRequestException ignored) {
                continue;
            }
            List<PreparedChildStart> preparedStarts =
                    new ArrayList<>();
            for (GroupChatToolCall call :
                    recordedStartCalls(step.getId())) {
                PreparedChildStart prepared = readPreparedStart(
                        execution, call.getToolArguments());
                if (prepared != null) {
                    ensureDistinctDestinationAndInvestigators(
                            prepared.sceneName(), prepared.selected(),
                            preparedStarts);
                    preparedStarts.add(prepared);
                }
            }
            if (!preparedStarts.isEmpty()) {
                childPlanService.startChildrenUnderLock(
                        execution.conversation(), execution.plan(),
                        preparedStarts.stream()
                                .map(prepared -> new TrpgChildScenePlanService
                                        .ChildSceneStart(
                                        prepared.sceneName(), step.getId(),
                                        prepared.selected().items()))
                                .toList());
                preparedStarts.stream()
                        .flatMap(prepared -> prepared.selected().items()
                                .stream())
                        .map(GroupReplyPlanItem::getSubjectCharacterId)
                        .distinct()
                        .forEach(characterId -> progressStore.clearReady(
                                execution.conversation().getId(),
                                execution.plan().getId(), characterId));
                return true;
            }
        }
        return false;
    }

    @Transactional(rollbackFor = Exception.class)
    public String resumeWaitingInvestigators(
            Long conversationId,
            Long replyStepId,
            List<String> investigatorNames) {
        SceneExecution execution = requireKpSceneExecution(
                conversationId, replyStepId);
        Selected selected = selectItems(
                execution, investigatorNames,
                GroupChatConstant.PARTICIPANT_WAITING);
        LocalDateTime now = LocalDateTime.now();
        for (GroupReplyPlanItem item : selected.items()) {
            item.setParticipantStatus(
                            GroupChatConstant.PARTICIPANT_ACTIVE)
                    .setUpdatedAt(now);
            itemMapper.updateById(item);
            progressStore.clearReady(
                    conversationId, execution.plan().getId(),
                    item.getSubjectCharacterId());
        }
        return String.join("、", selected.names())
                + "已结束等待，将从下一轮开始正常参与行动。";
    }

    public boolean canStartChildScene(
            GroupConversation conversation) {
        try {
            GroupReplyPlan scene = requireActiveScene(conversation);
            return scene.getParentPlanId() == null
                    && !activeInvestigatorItems(scene.getId()).isEmpty();
        } catch (UserRequestException ignored) {
            return false;
        }
    }

    public boolean isActiveChildScene(
            GroupConversation conversation) {
        try {
            return requireActiveScene(conversation)
                    .getParentPlanId() != null;
        } catch (UserRequestException ignored) {
            return false;
        }
    }

    public boolean hasWaitingInvestigators(
            GroupConversation conversation) {
        try {
            GroupReplyPlan scene = requireActiveScene(conversation);
            return investigatorItems(scene.getId()).stream()
                    .anyMatch(item ->
                            GroupChatConstant.PARTICIPANT_WAITING.equals(
                                    item.getParticipantStatus()));
        } catch (UserRequestException ignored) {
            return false;
        }
    }

    private SceneExecution requireKpSceneExecution(
            Long conversationId, Long replyStepId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        GroupChatReplyStep step = stepMapper.selectById(replyStepId);
        GroupChatTurn turn = step == null
                ? null : turnMapper.selectById(step.getTurnId());
        return requireKpSceneExecution(conversation, step, turn);
    }

    private SceneExecution requireKpSceneExecution(
            GroupConversation conversation,
            GroupChatReplyStep step,
            GroupChatTurn turn) {
        Long conversationId = conversation == null
                ? null : conversation.getId();
        if (step == null || turn == null
                || !conversationId.equals(turn.getConversationId())
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                turn.getPlanSource())) {
            throw new UserRequestException(
                    "当前回复步骤不属于场景探索");
        }
        if (!GroupChatConstant.ACTOR_KP.equals(
                step.getSpeakerType())) {
            throw new UserAuthException("只有KP可以管理子场景");
        }
        GroupReplyPlan plan = requireActiveScene(conversation);
        if (!plan.getId().equals(turn.getPlanId())) {
            throw new UserRequestException("当前场景回复计划已变化");
        }
        return new SceneExecution(
                conversation, step, turn, plan);
    }

    private List<GroupChatToolCall> recordedStartCalls(
            Long replyStepId) {
        return toolCallMapper.selectList(
                new LambdaQueryWrapper<GroupChatToolCall>()
                        .eq(GroupChatToolCall::getReplyStepId,
                                replyStepId)
                        .eq(GroupChatToolCall::getToolName,
                                START_CHILD_SCENE_TOOL)
                        .isNotNull(GroupChatToolCall::getToolResult)
                        .orderByAsc(GroupChatToolCall::getId));
    }

    private PreparedChildStart readPreparedStart(
            SceneExecution execution,
            String toolArguments) {
        try {
            ChildSceneStartArguments arguments =
                    objectMapper.readValue(
                            toolArguments,
                            ChildSceneStartArguments.class);
            String sceneName = normalizeSceneName(
                    arguments.childSceneName());
            Selected selected = selectItems(
                    execution,
                    arguments.investigatorNames(),
                    GroupChatConstant.PARTICIPANT_ACTIVE);
            return new PreparedChildStart(sceneName, selected);
        } catch (JacksonException | UserRequestException ignored) {
            return null;
        }
    }

    private GroupReplyPlan requireActiveScene(
            GroupConversation conversation) {
        if (conversation == null
                || conversation.getActiveReplyPlanId() == null) {
            throw new UserRequestException("当前没有活动场景");
        }
        GroupReplyPlan plan = planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (plan == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                plan.getSource())) {
            throw new UserRequestException("当前没有活动探索场景");
        }
        return plan;
    }

    private void requireRootSceneForSwitch(GroupReplyPlan scene) {
        if (scene != null && scene.getParentPlanId() != null) {
            throw new UserRequestException(
                    "当前调查员已处于子场景中，请结束当前场景后再进行后续切换");
        }
    }

    private String normalizeSceneName(String sceneName) {
        if (!StringUtils.hasText(sceneName)) {
            throw new UserRequestException("子场景名称不能为空");
        }
        String normalized = sceneName.trim();
        if (normalized.length() > 200) {
            throw new UserRequestException("子场景名称不能超过200个字符");
        }
        return normalized;
    }

    private Selected selectItems(
            SceneExecution execution,
            List<String> suppliedNames,
            String requiredStatus) {
        List<String> names = normalizeNames(suppliedNames);
        List<GroupReplyPlanItem> investigatorItems =
                investigatorItems(execution.plan().getId());
        Map<String, List<GroupReplyPlanItem>> itemsByName =
                new LinkedHashMap<>();
        for (GroupReplyPlanItem item :
                investigatorItems) {
            String actualStatus = item.getParticipantStatus();
            if (!requiredStatus.equals(actualStatus)) {
                continue;
            }
            String name = item.getSubjectCharacterName();
            if (!StringUtils.hasText(name)) {
                throw new UserRequestException(
                        "场景调查员名称快照不存在");
            }
            itemsByName.computeIfAbsent(
                    name, ignored -> new ArrayList<>())
                    .add(item);
        }
        List<GroupReplyPlanItem> selected = new ArrayList<>();
        for (String name : names) {
            List<GroupReplyPlanItem> matches =
                    itemsByName.getOrDefault(name, List.of());
            if (matches.size() != 1) {
                throw new UserRequestException(
                        "调查员名称不存在、重名或状态不符合："
                                + name);
            }
            selected.add(matches.getFirst());
        }
        return new Selected(
                List.copyOf(selected), List.copyOf(names));
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

    private void ensureDistinctDestinationAndInvestigators(
            String sceneName,
            Selected selected,
            List<PreparedChildStart> existing) {
        Set<Long> selectedCharacters = selected.items().stream()
                .map(GroupReplyPlanItem::getSubjectCharacterId)
                .collect(java.util.stream.Collectors.toSet());
        for (PreparedChildStart prepared : existing) {
            if (sceneName.equals(prepared.sceneName())) {
                throw new UserRequestException(
                        "同一目的地只应调用一次子场景工具");
            }
            boolean overlaps = prepared.selected().items().stream()
                    .map(GroupReplyPlanItem::getSubjectCharacterId)
                    .anyMatch(selectedCharacters::contains);
            if (overlaps) {
                throw new UserRequestException(
                        "同一调查员不能在本步骤前往多个子场景");
            }
        }
    }

    private List<GroupReplyPlanItem> activeInvestigatorItems(
            Long planId) {
        return investigatorItems(planId).stream()
                .filter(item ->
                        GroupChatConstant.PARTICIPANT_ACTIVE.equals(
                                item.getParticipantStatus()))
                .toList();
    }

    private List<GroupReplyPlanItem> investigatorItems(Long planId) {
        return itemMapper.selectList(
                        new LambdaQueryWrapper<GroupReplyPlanItem>()
                                .eq(GroupReplyPlanItem::getPlanId,
                                        planId)
                                .orderByAsc(
                                        GroupReplyPlanItem::getItemOrder)
                                .orderByAsc(
                                        GroupReplyPlanItem::getId))
                .stream()
                .filter(item ->
                        GroupChatConstant.ACTOR_USER.equals(
                                item.getActorType())
                                || GroupChatConstant.ACTOR_CHARACTER.equals(
                                item.getActorType()))
                .toList();
    }

    private record SceneExecution(
            GroupConversation conversation,
            GroupChatReplyStep step,
            GroupChatTurn turn,
            GroupReplyPlan plan) {
    }

    private record ChildSceneStartArguments(
            String childSceneName,
            List<String> investigatorNames) {
    }

    private record PreparedChildStart(
            String sceneName,
            Selected selected) {
    }

    private record Selected(
            List<GroupReplyPlanItem> items,
            List<String> names) {
    }
}
