package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GroupTurnRecoveryService {

    private final GroupChatTurnMapper turnMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatMessageMapper messageMapper;

    /** Caller must hold the conversation lock. */
    @Transactional(rollbackFor = Exception.class)
    public void recoverInterrupted(Long conversationId) {
        List<Long> turnIds = turnMapper.selectList(new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId, conversationId)
                        .in(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_PENDING,
                                GroupChatConstant.STATUS_RUNNING))
                .stream()
                .map(GroupChatTurn::getId)
                .toList();
        if (turnIds.isEmpty()) {
            return;
        }
        List<GroupChatReplyStep> interruptedSteps =
                stepMapper.selectList(
                        new LambdaQueryWrapper<
                                GroupChatReplyStep>()
                                .in(GroupChatReplyStep::getTurnId,
                                        turnIds)
                                .eq(GroupChatReplyStep::getStatus,
                                        GroupChatConstant
                                                .STATUS_RUNNING));
        Map<Long, Integer> retryableStepNosByTurn =
                new LinkedHashMap<>();
        for (GroupChatReplyStep step : interruptedSteps) {
            if (isRetryableTrpgStep(step)) {
                retryableStepNosByTurn.merge(
                        step.getTurnId(),
                        step.getStepNo(),
                        Math::min);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        messageMapper.update(
                new GroupChatMessage()
                        .setStatus(GroupChatConstant.STATUS_FAILED)
                        .setUpdatedAt(now),
                new LambdaUpdateWrapper<GroupChatMessage>()
                        .in(GroupChatMessage::getTurnId, turnIds)
                        .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_STREAMING));
        stepMapper.update(
                new GroupChatReplyStep()
                        .setStatus(GroupChatConstant.STATUS_FAILED)
                        .setErrorMessage("服务中断")
                        .setUpdatedAt(now),
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .in(GroupChatReplyStep::getTurnId, turnIds)
                        .eq(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_RUNNING));
        for (Map.Entry<Long, Integer> entry
                : retryableStepNosByTurn.entrySet()) {
            stepMapper.update(
                    new GroupChatReplyStep()
                            .setStatus(
                                    GroupChatConstant.STATUS_BLOCKED)
                            .setErrorMessage("前序回复未完成")
                            .setUpdatedAt(now),
                    new LambdaUpdateWrapper<
                            GroupChatReplyStep>()
                            .eq(GroupChatReplyStep::getTurnId,
                                    entry.getKey())
                            .gt(GroupChatReplyStep::getStepNo,
                                    entry.getValue())
                            .eq(GroupChatReplyStep::getStatus,
                                    GroupChatConstant
                                            .STATUS_PENDING));
        }
        List<Long> ordinaryTurnIds = turnIds.stream()
                .filter(turnId ->
                        !retryableStepNosByTurn.containsKey(turnId))
                .toList();
        if (!ordinaryTurnIds.isEmpty()) {
            stepMapper.update(
                    new GroupChatReplyStep()
                            .setStatus(
                                    GroupChatConstant
                                            .STATUS_CANCELLED)
                            .setErrorMessage("前序回复未完成")
                            .setUpdatedAt(now),
                    new LambdaUpdateWrapper<
                            GroupChatReplyStep>()
                            .in(GroupChatReplyStep::getTurnId,
                                    ordinaryTurnIds)
                            .eq(GroupChatReplyStep::getStatus,
                                    GroupChatConstant
                                            .STATUS_PENDING));
        }
        turnMapper.update(
                new GroupChatTurn()
                        .setStatus(GroupChatConstant.STATUS_FAILED)
                        .setUpdatedAt(now),
                new LambdaUpdateWrapper<GroupChatTurn>()
                        .in(GroupChatTurn::getId, turnIds)
                        .in(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_PENDING,
                                GroupChatConstant.STATUS_RUNNING));
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelPendingSteps(Long turnId, String reason) {
        stepMapper.update(
                new GroupChatReplyStep()
                        .setStatus(GroupChatConstant.STATUS_CANCELLED)
                        .setErrorMessage(reason)
                        .setUpdatedAt(LocalDateTime.now()),
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .eq(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_PENDING));
    }

    @Transactional(rollbackFor = Exception.class)
    public void blockPendingSteps(Long turnId, String reason) {
        stepMapper.update(
                new GroupChatReplyStep()
                        .setStatus(GroupChatConstant.STATUS_BLOCKED)
                        .setErrorMessage(reason)
                        .setUpdatedAt(LocalDateTime.now()),
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .eq(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_PENDING));
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelPendingInvestigatorSteps(
            Long turnId, String reason) {
        stepMapper.update(
                new GroupChatReplyStep()
                        .setStatus(GroupChatConstant.STATUS_CANCELLED)
                        .setErrorMessage(reason)
                        .setUpdatedAt(LocalDateTime.now()),
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .eq(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_PENDING)
                        .in(GroupChatReplyStep::getSpeakerType,
                                GroupChatConstant.ACTOR_USER,
                                GroupChatConstant.ACTOR_CHARACTER));
    }

    public void assertNoNonTerminalTurns(Long userWorldId) {
        if (positive(turnMapper.countNonTerminalByUserWorldId(userWorldId))) {
            throw new UserRequestException("当前世界存在未完成的群聊轮次");
        }
    }

    public void assertConversationHasNoNonTerminalTurns(Long conversationId) {
        if (positive(turnMapper.countNonTerminalByConversationId(conversationId))) {
            throw new UserRequestException("当前群聊存在未完成的轮次");
        }
    }

    public void assertConversationHasNoNonTerminalTurnsExcept(
            Long conversationId, Long excludedTurnId) {
        if (positive(turnMapper.countNonTerminalByConversationIdExcept(
                conversationId, excludedTurnId))) {
            throw new UserRequestException("当前群聊存在其他未完成的轮次");
        }
    }

    private boolean positive(Long value) {
        return value != null && value > 0;
    }

    private boolean isRetryableTrpgStep(
            GroupChatReplyStep step) {
        return GroupChatConstant.ACTION_TRPG_SCENE.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_TRPG_COMBAT.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_TRPG_SCENE_INTRO.equals(
                step.getActionType())
                || GroupChatConstant
                .ACTION_TRPG_SCENE_SELECTION.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                step.getActionType())
                || GroupChatConstant
                .ACTION_COMBAT_REACTION_ROUTE.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_COMBAT_DEFENSE.equals(
                step.getActionType())
                || GroupChatConstant
                .ACTION_COMBAT_ADJUDICATE.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE.equals(
                step.getActionType());
    }
}
