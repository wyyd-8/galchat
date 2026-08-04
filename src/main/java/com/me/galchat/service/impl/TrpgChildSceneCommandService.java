package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.CocModuleLocationMapper;
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
import java.util.HashMap;
import java.util.HashSet;
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
    private final CocModuleLocationMapper locationMapper;
    private final TrpgParticipantService participantService;
    private final TrpgChildScenePlanService childPlanService;
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
        boolean alreadyRequested = recordedStartCalls(replyStepId)
                .stream()
                .map(call -> readPreparedStart(
                        execution, call.getToolArguments()))
                .anyMatch(java.util.Objects::nonNull);
        if (alreadyRequested) {
            throw new UserRequestException(
                    "同一回复步骤不能重复创建子场景");
        }
        CocModuleLocation location = requireDescendantLocation(
                execution.conversation(),
                execution.plan().getContextId(),
                childSceneName);
        Selected selected = selectItems(
                execution, investigatorNames,
                GroupChatConstant.PARTICIPANT_ACTIVE);
        return "已创建子场景“" + location.getName()
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
            } catch (UserRequestException ignored) {
                continue;
            }
            for (GroupChatToolCall call : recordedStartCalls(
                    step.getId())) {
                PreparedChildStart prepared = readPreparedStart(
                        execution, call.getToolArguments());
                if (prepared == null) {
                    continue;
                }
                childPlanService.startChildUnderLock(
                        execution.conversation(),
                        execution.plan(),
                        prepared.location(),
                        prepared.selected().items());
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
        }
        return String.join("、", selected.names())
                + "已结束等待，将从下一轮开始正常参与行动。";
    }

    public boolean canStartChildScene(
            GroupConversation conversation) {
        try {
            GroupReplyPlan scene = requireActiveScene(conversation);
            if (activeInvestigatorItems(scene.getId()).isEmpty()) {
                return false;
            }
            List<CocModuleLocation> locations =
                    locationMapper.selectList(
                            new LambdaQueryWrapper<CocModuleLocation>()
                                    .eq(CocModuleLocation::getModuleId,
                                            conversation.getModuleId()));
            Map<Long, CocModuleLocation> byId = new HashMap<>();
            locations.forEach(location ->
                    byId.put(location.getId(), location));
            return locations.stream()
                    .filter(location ->
                            StringUtils.hasText(location.getContent()))
                    .anyMatch(location -> isStrictDescendant(
                            location, scene.getContextId(), byId));
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
            CocModuleLocation location = requireDescendantLocation(
                    execution.conversation(),
                    execution.plan().getContextId(),
                    arguments.childSceneName());
            Selected selected = selectItems(
                    execution,
                    arguments.investigatorNames(),
                    GroupChatConstant.PARTICIPANT_ACTIVE);
            return new PreparedChildStart(location, selected);
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

    private CocModuleLocation requireDescendantLocation(
            GroupConversation conversation,
            Long currentLocationId,
            String locationName) {
        if (!StringUtils.hasText(locationName)) {
            throw new UserRequestException("子场景名称不能为空");
        }
        String exactName = locationName.trim();
        List<CocModuleLocation> locations =
                locationMapper.selectList(
                        new LambdaQueryWrapper<CocModuleLocation>()
                                .eq(CocModuleLocation::getModuleId,
                                        conversation.getModuleId()));
        Map<Long, CocModuleLocation> byId = new HashMap<>();
        locations.forEach(location ->
                byId.put(location.getId(), location));
        List<CocModuleLocation> matches = locations.stream()
                .filter(location ->
                        exactName.equals(location.getName()))
                .filter(location ->
                        StringUtils.hasText(location.getContent()))
                .filter(location -> isStrictDescendant(
                        location, currentLocationId, byId))
                .toList();
        if (matches.isEmpty()) {
            throw new UserRequestException(
                    "子场景必须是模组中有实际描述的后代地点");
        }
        if (matches.size() > 1) {
            throw new UserRequestException("子场景名称不唯一");
        }
        return matches.getFirst();
    }

    private boolean isStrictDescendant(
            CocModuleLocation location,
            Long ancestorId,
            Map<Long, CocModuleLocation> byId) {
        if (location == null
                || location.getId().equals(ancestorId)) {
            return false;
        }
        Set<Long> visited = new HashSet<>();
        CocModuleLocation current = location;
        while (current != null && visited.add(current.getId())) {
            if (ancestorId.equals(current.getParentLocationId())) {
                return true;
            }
            current = current.getParentLocationId() == null
                    ? null : byId.get(current.getParentLocationId());
        }
        return false;
    }

    private Selected selectItems(
            SceneExecution execution,
            List<String> suppliedNames,
            String requiredStatus) {
        List<String> names = normalizeNames(suppliedNames);
        Map<GroupActorRef, String> nameByActor =
                new LinkedHashMap<>();
        for (TrpgParticipantService.Participant participant :
                participantService.listInvestigators(
                        execution.conversation())) {
            nameByActor.put(participant.actor(),
                    participant.investigatorName());
        }
        Map<String, List<GroupReplyPlanItem>> itemsByName =
                new LinkedHashMap<>();
        for (GroupReplyPlanItem item :
                investigatorItems(execution.plan().getId())) {
            String actualStatus =
                    GroupChatConstant.PARTICIPANT_WAITING.equals(
                            item.getParticipantStatus())
                            ? GroupChatConstant.PARTICIPANT_WAITING
                            : GroupChatConstant.PARTICIPANT_ACTIVE;
            if (!requiredStatus.equals(actualStatus)) {
                continue;
            }
            String name = nameByActor.get(new GroupActorRef(
                    item.getActorType(), item.getActorId()));
            if (name != null) {
                itemsByName.computeIfAbsent(
                        name, ignored -> new ArrayList<>())
                        .add(item);
            }
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

    private List<GroupReplyPlanItem> activeInvestigatorItems(
            Long planId) {
        return investigatorItems(planId).stream()
                .filter(item ->
                        !GroupChatConstant.PARTICIPANT_WAITING.equals(
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
            CocModuleLocation location,
            Selected selected) {
    }

    private record Selected(
            List<GroupReplyPlanItem> items,
            List<String> names) {
    }
}
