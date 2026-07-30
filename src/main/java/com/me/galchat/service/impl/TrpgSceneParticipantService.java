package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TrpgSceneParticipantService {

    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final CocModuleLocationMapper locationMapper;
    private final TrpgParticipantService participantService;

    public SceneState state(GroupConversation conversation) {
        GroupReplyPlan scene = requireActiveScene(conversation);
        Map<GroupActorRef, String> names = new HashMap<>();
        for (TrpgParticipantService.Participant participant :
                participantService.listInvestigators(conversation)) {
            names.put(participant.actor(),
                    participant.investigatorName());
        }
        List<String> active = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (GroupReplyPlanItem item : orderedItems(scene.getId())) {
            if (!isInvestigator(item)) {
                continue;
            }
            String name = names.get(new GroupActorRef(
                    item.getActorType(), item.getActorId()));
            if (name == null) {
                throw new UserRequestException(
                        "场景调查员人物卡不存在");
            }
            if (GroupChatConstant.PARTICIPANT_WAITING.equals(
                    item.getParticipantStatus())) {
                waiting.add(name);
            } else {
                active.add(name);
            }
        }
        return new SceneState(
                scene.getId(),
                scenePath(conversation.getModuleId(),
                        scene.getContextId()),
                List.copyOf(active),
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
                        .orderByAsc(GroupReplyPlanItem::getGroupOrder)
                        .orderByAsc(GroupReplyPlanItem::getItemOrder)
                        .orderByAsc(GroupReplyPlanItem::getId));
    }

    private String scenePath(Long moduleId, Long locationId) {
        List<CocModuleLocation> locations = locationMapper.selectList(
                new LambdaQueryWrapper<CocModuleLocation>()
                        .eq(CocModuleLocation::getModuleId, moduleId));
        Map<Long, CocModuleLocation> byId = new HashMap<>();
        locations.forEach(location ->
                byId.put(location.getId(), location));
        List<String> names = new ArrayList<>();
        CocModuleLocation current = byId.get(locationId);
        while (current != null) {
            names.addFirst(current.getName());
            current = current.getParentLocationId() == null
                    ? null : byId.get(current.getParentLocationId());
        }
        if (names.isEmpty()) {
            throw new UserRequestException("当前场景地点不存在");
        }
        return String.join(" - ", names);
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
            List<String> waitingInvestigatorNames) {
    }
}
