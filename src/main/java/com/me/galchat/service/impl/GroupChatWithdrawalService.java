package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.FavorBindingType;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.context.GroupTopicService;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.UserCharacterFavorLogMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class GroupChatWithdrawalService {

    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatToolCallMapper toolCallMapper;
    private final GroupTurnRecoveryService recoveryService;
    private final UserCharacterFavorLogMapper favorLogMapper;
    private final UserCharacterInfoMapper characterInfoMapper;
    private final StringRedisTemplate redisTemplate;
    private final GroupTopicService topicService;
    private final TransactionTemplate transactionTemplate;

    public GroupChatWithdrawalService(GroupConversationService conversationService,
                                      GroupConversationLockService lockService,
                                      GroupChatTurnMapper turnMapper,
                                      GroupChatMessageMapper messageMapper,
                                      GroupChatReplyStepMapper stepMapper,
                                      GroupChatToolCallMapper toolCallMapper,
                                      GroupTurnRecoveryService recoveryService,
                                      UserCharacterFavorLogMapper favorLogMapper,
                                      UserCharacterInfoMapper characterInfoMapper,
                                      StringRedisTemplate redisTemplate,
                                      GroupTopicService topicService,
                                      TransactionTemplate transactionTemplate) {
        this.conversationService = conversationService;
        this.lockService = lockService;
        this.turnMapper = turnMapper;
        this.messageMapper = messageMapper;
        this.stepMapper = stepMapper;
        this.toolCallMapper = toolCallMapper;
        this.recoveryService = recoveryService;
        this.favorLogMapper = favorLogMapper;
        this.characterInfoMapper = characterInfoMapper;
        this.redisTemplate = redisTemplate;
        this.topicService = topicService;
        this.transactionTemplate = transactionTemplate;
    }

    public void withdrawLatestTurn(Long conversationId) {
        GroupConversation conversation = conversationService.requireActive(conversationId);
        if (!GroupChatConstant.MODE_CHAT.equals(conversation.getMode())) {
            throw new UserRequestException("跑团群聊不支持撤回");
        }
        GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
        if (lock == null) {
            throw new UserRequestException("当前群聊正在生成回复，请稍后再撤回");
        }
        try {
            GroupConversation lockedConversation = conversationService.requireActive(conversationId);
            if (!GroupChatConstant.MODE_CHAT.equals(lockedConversation.getMode())) {
                throw new UserRequestException("跑团群聊不支持撤回");
            }
            recoveryService.assertConversationHasNoNonTerminalTurns(conversationId);
            transactionTemplate.executeWithoutResult(status -> withdrawLocked(lockedConversation));
        } finally {
            lockService.unlock(lock);
        }
    }

    private void withdrawLocked(GroupConversation conversation) {
        List<GroupChatTurn> recentTurns = turnMapper.selectList(new LambdaQueryWrapper<GroupChatTurn>()
                .eq(GroupChatTurn::getConversationId, conversation.getId())
                .orderByDesc(GroupChatTurn::getId)
                .last("limit " + (GroupChatConstant.MAX_CONSECUTIVE_WITHDRAW_COUNT + 1)));
        WithdrawCandidate candidate = selectWithdrawCandidate(recentTurns);
        if (candidate.consecutiveWithdrawCount() >= GroupChatConstant.MAX_CONSECUTIVE_WITHDRAW_COUNT) {
            throw new UserRequestException("最多只能连续撤回3轮群聊");
        }
        if (candidate.turn() == null) {
            throw new UserRequestException("没有可撤回的群聊轮次");
        }

        GroupChatTurn turn = candidate.turn();
        GroupChatMessage trigger = turn.getTriggerMessageId() == null
                ? null : messageMapper.selectById(turn.getTriggerMessageId());
        List<GroupChatReplyStep> steps = stepMapper.selectList(new LambdaQueryWrapper<GroupChatReplyStep>()
                .eq(GroupChatReplyStep::getTurnId, turn.getId())
                .orderByAsc(GroupChatReplyStep::getStepNo));
        List<Long> stepIds = steps.stream().map(GroupChatReplyStep::getId).filter(Objects::nonNull).toList();

        rollbackFavor(conversation.getUserWorldId(), stepIds);
        if (!stepIds.isEmpty()) {
            toolCallMapper.delete(new LambdaQueryWrapper<GroupChatToolCall>()
                    .in(GroupChatToolCall::getReplyStepId, stepIds));
        }
        messageMapper.delete(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getTurnId, turn.getId()));
        stepMapper.delete(new LambdaQueryWrapper<GroupChatReplyStep>()
                .eq(GroupChatReplyStep::getTurnId, turn.getId()));

        if (trigger != null) {
            topicService.rollbackTurnBoundary(conversation, trigger.getSequenceNo());
        }
        turn.setTriggerMessageId(null)
                .setStatus(GroupChatConstant.STATUS_WITHDRAWN)
                .setRevision(Objects.requireNonNullElse(turn.getRevision(), 0) + 1)
                .setUpdatedAt(LocalDateTime.now());
        turnMapper.update(null, new LambdaUpdateWrapper<GroupChatTurn>()
                .eq(GroupChatTurn::getId, turn.getId())
                .set(GroupChatTurn::getTriggerMessageId, null)
                .set(GroupChatTurn::getStatus, turn.getStatus())
                .set(GroupChatTurn::getRevision, turn.getRevision())
                .set(GroupChatTurn::getUpdatedAt, turn.getUpdatedAt()));
    }

    private void rollbackFavor(Long userWorldId, List<Long> stepIds) {
        if (stepIds.isEmpty()) {
            return;
        }
        List<UserCharacterFavorLog> logs = favorLogMapper.selectList(
                new LambdaQueryWrapper<UserCharacterFavorLog>()
                        .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                        .eq(UserCharacterFavorLog::getBindingType, FavorBindingType.GROUP_REPLY_STEP)
                        .in(UserCharacterFavorLog::getBindingChat, stepIds));
        Map<Long, Integer> updateByCharacter = logs.stream()
                .filter(log -> log.getCharacterId() != null && log.getFavorUpdate() != null)
                .collect(Collectors.groupingBy(UserCharacterFavorLog::getCharacterId,
                        Collectors.summingInt(UserCharacterFavorLog::getFavorUpdate)));
        updateByCharacter.forEach((characterId, update) -> {
            Integer value = characterInfoMapper.updateFavorValue(userWorldId, characterId, -update);
            if (value != null) {
                redisTemplate.opsForHash().put(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY,
                        userWorldId + ":" + characterId, String.valueOf(value));
                redisTemplate.delete(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX
                        + userWorldId + ":" + characterId);
            }
        });
        favorLogMapper.delete(new LambdaQueryWrapper<UserCharacterFavorLog>()
                .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                .eq(UserCharacterFavorLog::getBindingType, FavorBindingType.GROUP_REPLY_STEP)
                .in(UserCharacterFavorLog::getBindingChat, stepIds));
    }

    static WithdrawCandidate selectWithdrawCandidate(List<GroupChatTurn> recentTurns) {
        int withdrawn = 0;
        for (GroupChatTurn turn : recentTurns) {
            if (GroupChatConstant.STATUS_WITHDRAWN.equals(turn.getStatus())) {
                withdrawn++;
                continue;
            }
            return new WithdrawCandidate(turn, withdrawn);
        }
        return new WithdrawCandidate(null, withdrawn);
    }

    record WithdrawCandidate(GroupChatTurn turn, int consecutiveWithdrawCount) {
    }
}
