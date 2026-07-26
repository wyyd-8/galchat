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
import java.util.List;

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
        stepMapper.update(
                new GroupChatReplyStep()
                        .setStatus(GroupChatConstant.STATUS_CANCELLED)
                        .setErrorMessage("前序回复未完成")
                        .setUpdatedAt(now),
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .in(GroupChatReplyStep::getTurnId, turnIds)
                        .eq(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_PENDING));
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

    private boolean positive(Long value) {
        return value != null && value > 0;
    }
}
