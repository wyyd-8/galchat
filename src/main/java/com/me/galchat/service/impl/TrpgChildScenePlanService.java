package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgChildScenePlanService {

    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final GroupConversationMapper conversationMapper;
    private final TrpgRuntimeChildSceneMapper runtimeSceneMapper;

    @Transactional(rollbackFor = Exception.class)
    public GroupReplyPlan startChildUnderLock(
            GroupConversation conversation,
            GroupReplyPlan parent,
            String sceneName,
            Long createdStepId,
            List<GroupReplyPlanItem> selectedInvestigators) {
        return startChildrenUnderLock(
                conversation, parent,
                List.of(new ChildSceneStart(
                        sceneName, createdStepId,
                        selectedInvestigators))).getFirst();
    }

    @Transactional(rollbackFor = Exception.class)
    public List<GroupReplyPlan> startChildrenUnderLock(
            GroupConversation conversation,
            GroupReplyPlan parent,
            List<ChildSceneStart> starts) {
        if (conversation == null || parent == null
                || starts == null || starts.isEmpty()
                || !parent.getId().equals(
                conversation.getActiveReplyPlanId())) {
            throw new UserRequestException("创建子场景参数不完整");
        }
        LocalDateTime now = LocalDateTime.now();
        java.util.ArrayList<GroupReplyPlan> children =
                new java.util.ArrayList<>();
        GroupReplyPlan previous = null;
        for (ChildSceneStart start : starts) {
            if (start == null || start.sceneName() == null
                    || start.createdStepId() == null
                    || start.selectedInvestigators() == null
                    || start.selectedInvestigators().isEmpty()) {
                throw new UserRequestException("创建子场景参数不完整");
            }
            GroupReplyPlan child = insertChild(
                    conversation, parent, start, now);
            if (previous != null) {
                previous.setNextPlanId(child.getId())
                        .setUpdatedAt(now);
                planMapper.updateById(previous);
            }
            children.add(child);
            previous = child;
        }
        conversation.setActiveReplyPlanId(children.getFirst().getId())
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return List.copyOf(children);
    }

    private GroupReplyPlan insertChild(
            GroupConversation conversation,
            GroupReplyPlan parent,
            ChildSceneStart start,
            LocalDateTime now) {
        GroupReplyPlan child = new GroupReplyPlan()
                .setConversationId(conversation.getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(parent.getContextId())
                .setExecutionKey("scene:" + parent.getId() + ":child")
                .setDisplayName(start.sceneName())
                .setParentPlanId(parent.getId())
                .setCreatedAt(now)
                .setUpdatedAt(now);
        planMapper.insert(child);
        runtimeSceneMapper.insert(new TrpgRuntimeChildScene()
                .setPlanId(child.getId())
                .setConversationId(conversation.getId())
                .setSceneName(start.sceneName())
                .setCreatedStepId(start.createdStepId())
                .setCreatedAt(now));
        child.setExecutionKey("scene:" + child.getId());
        planMapper.updateById(child);
        int order = 1;
        for (GroupReplyPlanItem selected :
                start.selectedInvestigators()) {
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(child.getId())
                    .setItemOrder(order++)
                    .setActorType(selected.getActorType())
                    .setActorId(selected.getActorId())
                    .setSubjectCharacterId(
                            selected.getSubjectCharacterId())
                    .setSubjectCharacterName(
                            selected.getSubjectCharacterName())
                    .setParticipantStatus(
                            GroupChatConstant.PARTICIPANT_ACTIVE)
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
        itemMapper.insert(new GroupReplyPlanItem()
                .setPlanId(child.getId())
                .setItemOrder(order)
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_ACTIVE)
                .setCreatedAt(now)
                .setUpdatedAt(now));
        return child;
    }

    @Transactional(rollbackFor = Exception.class)
    public GroupReplyPlan finishChildUnderLock(
            GroupConversation conversation,
            GroupReplyPlan child) {
        if (conversation == null || child == null
                || child.getParentPlanId() == null
                || !child.getId().equals(
                conversation.getActiveReplyPlanId())) {
            throw new UserRequestException("关闭子场景参数不完整");
        }
        GroupReplyPlan parent = planMapper.selectById(
                child.getParentPlanId());
        if (parent == null
                || !conversation.getId().equals(
                parent.getConversationId())
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                parent.getSource())) {
            throw new UserRequestException("父场景计划不存在");
        }
        Set<Long> childCharacters = orderedItems(child.getId())
                .stream()
                .filter(this::isInvestigator)
                .map(GroupReplyPlanItem::getSubjectCharacterId)
                .collect(Collectors.toSet());
        LocalDateTime now = LocalDateTime.now();
        for (GroupReplyPlanItem parentItem :
                orderedItems(parent.getId())) {
            if (!childCharacters.contains(
                    parentItem.getSubjectCharacterId())) {
                continue;
            }
            parentItem.setParticipantStatus(
                            GroupChatConstant.PARTICIPANT_WAITING)
                    .setUpdatedAt(now);
            itemMapper.updateById(parentItem);
        }
        itemMapper.delete(
                new LambdaQueryWrapper<GroupReplyPlanItem>()
                        .eq(GroupReplyPlanItem::getPlanId,
                                child.getId()));
        runtimeSceneMapper.deleteById(child.getId());
        planMapper.deleteById(child.getId());
        GroupReplyPlan resume = parent;
        if (child.getNextPlanId() != null) {
            GroupReplyPlan next = planMapper.selectById(
                    child.getNextPlanId());
            if (next == null
                    || !conversation.getId().equals(
                    next.getConversationId())
                    || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                    next.getSource())
                    || !parent.getId().equals(next.getParentPlanId())) {
                throw new UserRequestException("下一子场景计划不存在");
            }
            resume = next;
        }
        conversation.setActiveReplyPlanId(resume.getId())
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return resume;
    }

    private List<GroupReplyPlanItem> orderedItems(Long planId) {
        return itemMapper.selectList(
                new LambdaQueryWrapper<GroupReplyPlanItem>()
                        .eq(GroupReplyPlanItem::getPlanId, planId)
                        .orderByAsc(GroupReplyPlanItem::getItemOrder)
                        .orderByAsc(GroupReplyPlanItem::getId));
    }

    private boolean isInvestigator(GroupReplyPlanItem item) {
        return GroupChatConstant.ACTOR_USER.equals(item.getActorType())
                || GroupChatConstant.ACTOR_CHARACTER.equals(
                item.getActorType());
    }

    public record ChildSceneStart(
            String sceneName,
            Long createdStepId,
            List<GroupReplyPlanItem> selectedInvestigators) {
    }
}
