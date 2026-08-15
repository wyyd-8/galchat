package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgSceneParticipantService {

    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final CocModuleLocationMapper locationMapper;
    private final TrpgRuntimeChildSceneMapper runtimeSceneMapper;

    public SceneState state(GroupConversation conversation) {
        GroupReplyPlan scene = requireActiveScene(conversation);
        List<GroupReplyPlanItem> items = orderedItems(scene.getId());
        List<String> active = new ArrayList<>();
        List<Long> activeCharacterIds = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (GroupReplyPlanItem item : items) {
            if (!isInvestigator(item)) {
                continue;
            }
            String name = item.getSubjectCharacterName();
            if (!StringUtils.hasText(name)) {
                throw new UserRequestException(
                        "场景调查员名称快照不存在");
            }
            if (GroupChatConstant.PARTICIPANT_WAITING.equals(
                    item.getParticipantStatus())) {
                waiting.add(name);
            } else if (GroupChatConstant.PARTICIPANT_ACTIVE.equals(
                    item.getParticipantStatus())) {
                if (item.getSubjectCharacterId() == null) {
                    throw new UserRequestException(
                            "场景调查员人物卡快照不存在");
                }
                active.add(name);
                activeCharacterIds.add(item.getSubjectCharacterId());
            }
        }
        return new SceneState(
                scene.getId(),
                scenePath(conversation.getModuleId(), scene),
                List.copyOf(active),
                List.copyOf(activeCharacterIds),
                List.copyOf(waiting));
    }

    public boolean hasWaiting(GroupConversation conversation) {
        return !state(conversation)
                .waitingInvestigatorNames().isEmpty();
    }

    public boolean hasActive(GroupConversation conversation) {
        return !state(conversation)
                .activeInvestigatorNames().isEmpty();
    }

    public GroupReplyPlan requireActiveScene(
            GroupConversation conversation) {
        if (conversation == null
                || conversation.getActiveReplyPlanId() == null) {
            throw new UserRequestException("当前没有活动场景");
        }
        GroupReplyPlan active = planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (active != null
                && GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                active.getSource())
                && active.getResumePlanId() != null) {
            active = planMapper.selectById(active.getResumePlanId());
        }
        if (active == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                active.getSource())) {
            throw new UserRequestException("当前没有活动探索场景");
        }
        return active;
    }

    public List<GroupReplyPlanItem> orderedItems(Long planId) {
        return itemMapper.selectList(
                new LambdaQueryWrapper<GroupReplyPlanItem>()
                        .eq(GroupReplyPlanItem::getPlanId, planId)
                        .orderByAsc(GroupReplyPlanItem::getItemOrder)
                        .orderByAsc(GroupReplyPlanItem::getId));
    }

    private String scenePath(
            Long moduleId, GroupReplyPlan activeScene) {
        List<GroupReplyPlan> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        GroupReplyPlan current = activeScene;
        while (current != null) {
            if (!visited.add(current.getId())) {
                throw new UserRequestException("场景计划父链存在循环");
            }
            chain.addFirst(current);
            current = current.getParentPlanId() == null
                    ? null : planMapper.selectById(
                    current.getParentPlanId());
        }
        GroupReplyPlan root = chain.getFirst();
        StringBuilder result = new StringBuilder(
                moduleLocationName(moduleId, root.getContextId()));
        for (int index = 1; index < chain.size(); index++) {
            GroupReplyPlan child = chain.get(index);
            TrpgRuntimeChildScene runtime =
                    runtimeSceneMapper.selectById(child.getId());
            if (runtime != null) {
                result.append(" - ").append(runtime.getSceneName());
            } else {
                throw new UserRequestException("动态子场景数据不存在");
            }
        }
        return result.toString();
    }

    private String moduleLocationName(Long moduleId, Long locationId) {
        CocModuleLocation location = locationMapper.selectById(locationId);
        if (location == null
                || !java.util.Objects.equals(
                moduleId, location.getModuleId())) {
            throw new UserRequestException("当前场景地点不存在");
        }
        return location.getName();
    }

    private boolean isInvestigator(GroupReplyPlanItem item) {
        return GroupChatConstant.ACTOR_USER.equals(item.getActorType())
                || GroupChatConstant.ACTOR_CHARACTER.equals(
                item.getActorType());
    }

    public record SceneState(
            Long planId,
            String scenePath,
            List<String> activeInvestigatorNames,
            List<Long> activeInvestigatorCharacterIds,
            List<String> waitingInvestigatorNames) {
    }
}
