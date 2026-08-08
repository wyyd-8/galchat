package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.TrpgGameTimePeriod;
import com.me.galchat.domain.dto.TrpgGameTimeUpdateDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.TrpgGameTimeVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TrpgGameTimeService {

    private final GroupConversationMapper conversationMapper;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final GroupTurnRecoveryService recoveryService;
    private final GroupConversationLockService lockService;

    public TrpgGameTimeVO correct(
            Long userId,
            Long conversationId,
            TrpgGameTimeUpdateDTO request) {
        validateRequest(request);
        GroupConversation beforeLock = requireWritable(
                userId, conversationId);
        if (TrpgGameTimeVO.from(beforeLock) == null) {
            throw new UserRequestException(
                    "当前时间尚未由KP初始化");
        }
        GroupConversationLockService.OwnedLock lock =
                lockService.tryLock(conversationId);
        if (lock == null) {
            throw new UserRequestException(
                    "跑团正在生成回复，请稍后再修改时间");
        }
        try {
            GroupConversation current = requireWritable(
                    userId, conversationId);
            if (TrpgGameTimeVO.from(current) == null) {
                throw new UserRequestException(
                        "当前时间尚未由KP初始化");
            }
            if (!Objects.equals(
                    current.getGameTimeRevision(),
                    request.revision())) {
                throw new UserRequestException(
                        "当前时间已发生变化，请刷新后重试");
            }
            recoveryService
                    .assertConversationHasNoNonTerminalTurns(
                            conversationId);
            return update(current, request);
        } finally {
            lockService.unlock(lock);
        }
    }

    private TrpgGameTimeVO update(
            GroupConversation conversation,
            TrpgGameTimeUpdateDTO request) {
        TrpgGameTimePeriod period =
                TrpgGameTimePeriod.parse(request.period());
        int nextRevision = conversation.getGameTimeRevision() + 1;
        LocalDateTime now = LocalDateTime.now();
        int updated = conversationMapper.update(
                null,
                new LambdaUpdateWrapper<GroupConversation>()
                        .eq(GroupConversation::getId,
                                conversation.getId())
                        .eq(GroupConversation::getGameTimeRevision,
                                conversation.getGameTimeRevision())
                        .set(GroupConversation::getGameDayNo,
                                request.dayNo())
                        .set(GroupConversation::getGameTimePeriod,
                                period.name())
                        .set(GroupConversation::getGameTimeRevision,
                                nextRevision)
                        .set(GroupConversation::getGameTimeChangedStepId,
                                null)
                        .set(GroupConversation::getGameTimeUpdatedAt,
                                now)
                        .set(GroupConversation::getUpdatedAt, now));
        if (updated == 0) {
            throw new UserRequestException(
                    "当前时间已发生变化，请刷新后重试");
        }
        conversation.setGameDayNo(request.dayNo())
                .setGameTimePeriod(period.name())
                .setGameTimeRevision(nextRevision)
                .setGameTimeChangedStepId(null)
                .setGameTimeUpdatedAt(now)
                .setUpdatedAt(now);
        return TrpgGameTimeVO.from(conversation);
    }

    private GroupConversation requireWritable(
            Long userId, Long conversationId) {
        if (conversationId == null) {
            throw new UserRequestException("跑团群聊id不能为空");
        }
        GroupConversation conversation =
                conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new UserRequestException("跑团群聊不存在");
        }
        userWorldPrefixService.checkUserWorldAuth(
                userId, conversation.getUserWorldId(), true);
        if (!GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException(
                    "只有TRPG群聊可以修改游戏时间");
        }
        if (!GroupChatConstant.STATUS_ACTIVE.equals(
                conversation.getStatus())) {
            throw new UserRequestException("跑团群聊已结束");
        }
        return conversation;
    }

    private void validateRequest(
            TrpgGameTimeUpdateDTO request) {
        if (request == null
                || request.dayNo() == null
                || request.dayNo() <= 0) {
            throw new UserRequestException(
                    "游戏天数必须大于0");
        }
        TrpgGameTimePeriod.parse(request.period());
        if (request.revision() == null
                || request.revision() < 0) {
            throw new UserRequestException(
                    "时间版本不能为空");
        }
    }
}
