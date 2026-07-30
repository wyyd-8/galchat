package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
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

    @Transactional(rollbackFor = Exception.class)
    public GroupReplyPlan startChildUnderLock(
            GroupConversation conversation,
            GroupReplyPlan parent,
            CocModuleLocation location,
            List<GroupReplyPlanItem> selectedInvestigators) {
        if (conversation == null || parent == null
                || location == null
                || selectedInvestigators == null
                || selectedInvestigators.isEmpty()
                || !parent.getId().equals(
                conversation.getActiveReplyPlanId())) {
            throw new UserRequestException("创建子场景参数不完整");
        }
        LocalDateTime now = LocalDateTime.now();
        GroupReplyPlan child = new GroupReplyPlan()
                .setConversationId(conversation.getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(location.getId())
                .setParentPlanId(parent.getId())
                .setCreatedAt(now)
                .setUpdatedAt(now);
        planMapper.insert(child);
        String groupKey = "scene:" + location.getId();
        int order = 1;
        for (GroupReplyPlanItem selected :
                selectedInvestigators) {
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(child.getId())
                    .setGroupKey(groupKey)
                    .setGroupName(location.getName())
                    .setGroupOrder(1)
                    .setItemOrder(order++)
                    .setActorType(selected.getActorType())
                    .setActorId(selected.getActorId())
                    .setSubjectCharacterId(
                            selected.getSubjectCharacterId())
                    .setParticipantStatus(
                            GroupChatConstant.PARTICIPANT_ACTIVE)
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
        itemMapper.insert(new GroupReplyPlanItem()
                .setPlanId(child.getId())
                .setGroupKey(groupKey)
                .setGroupName(location.getName())
                .setGroupOrder(1)
                .setItemOrder(order)
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_ACTIVE)
                .setCreatedAt(now)
                .setUpdatedAt(now));
        conversation.setActiveReplyPlanId(child.getId())
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
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
        Set<GroupActorRef> childActors = orderedItems(child.getId())
                .stream()
                .filter(this::isInvestigator)
                .map(item -> new GroupActorRef(
                        item.getActorType(), item.getActorId()))
                .collect(Collectors.toSet());
        LocalDateTime now = LocalDateTime.now();
        for (GroupReplyPlanItem parentItem :
                orderedItems(parent.getId())) {
            if (!childActors.contains(new GroupActorRef(
                    parentItem.getActorType(),
                    parentItem.getActorId()))) {
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
        planMapper.deleteById(child.getId());
        conversation.setActiveReplyPlanId(parent.getId())
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return parent;
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
}
