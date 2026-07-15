package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterFavorLogMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.service.IUserChatHistoryService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@Service
@RequiredArgsConstructor
public class UserChatHistoryServiceImpl extends ServiceImpl<UserChatHistoryMapper, UserChatHistory> implements IUserChatHistoryService {

    private final IUserWorldPrefixService userWorldPrefixService;
    private final UserChatThinkingHistoryMapper userChatThinkingHistoryMapper;
    private final UserChatToolCallMapper userChatToolCallMapper;
    private final UserCharacterFavorLogMapper userCharacterFavorLogMapper;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final TopicBoundaryService topicBoundaryService;
    private final SingleChatLockService singleChatLockService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public List<UserChatHistory> listHistory(Long userWorldId, Long characterId, Long id, Integer size) {
        Boolean thinkStatus = userWorldPrefixService.checkUserWorldAuth(userWorldId, true).getThinkStatus();
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }

        List<UserChatHistory> primaryMessages = listPrimaryMessages(userWorldId, characterId, id, size);
        if (primaryMessages.isEmpty()) {
            return List.of();
        }

        if (!Boolean.TRUE.equals(thinkStatus)) {
            return listVisibleMessages(userWorldId, characterId, primaryMessages);
        }

        return listThinkingMessages(userWorldId, characterId, primaryMessages);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawLatestUserMessage(Long userWorldId, Long characterId) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }

        RLock conversationLock = singleChatLockService.tryLock(userWorldId, characterId);
        if (conversationLock == null) {
            throw new UserRequestException("当前单聊正在处理中，请稍后再撤回");
        }

        try {
            doWithdrawLatestUserMessage(userWorldId, characterId);
        } finally {
            singleChatLockService.unlock(conversationLock);
        }
    }

    private void doWithdrawLatestUserMessage(Long userWorldId, Long characterId) {
        WithdrawCandidate candidate = latestWithdrawCandidate(userWorldId, characterId);
        if (candidate.userMessage() == null) {
            throw new UserRequestException("没有可撤回的用户消息");
        }
        if (candidate.consecutiveWithdrawCount() >= ChatConstant.MAX_CONSECUTIVE_WITHDRAW_COUNT) {
            throw new UserRequestException("最多只能连续撤回3条消息");
        }

        Long userMessageId = candidate.userMessage().getId();
        Set<Long> deletedMessageIds = linkedHistoryIds(userWorldId, characterId, userMessageId);
        deletedMessageIds.add(userMessageId);

        reverseFavorUpdates(userWorldId, characterId, userMessageId);
        deleteLinkedMessages(userWorldId, characterId, userMessageId);
        markUserMessageWithdrawn(userWorldId, characterId, userMessageId);
        deleteAutoSearchInfoAfter(userWorldId, characterId, userMessageId);
        deleteStepNoKey(userMessageId);
        topicBoundaryService.clearBoundaryIfReferences(userWorldId, characterId, deletedMessageIds);
        refreshLatestChatInfo(userWorldId, characterId);
    }

    private List<UserChatHistory> listPrimaryMessages(Long userWorldId, Long characterId, Long id, Integer size) {
        List<UserChatHistory> primaryMessages = lambdaQuery()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .lt(id != null, UserChatHistory::getId, id)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue())
                        .or(unlinkedAssistantWrapper -> unlinkedAssistantWrapper
                                .eq(UserChatHistory::getType, MessageType.ASSISTANT.getValue())
                                .isNull(UserChatHistory::getUserMessageId)))
                .orderByDesc(UserChatHistory::getId)
                .last("limit " + size)
                .list();
        Collections.reverse(primaryMessages);
        return primaryMessages;
    }

    private WithdrawCandidate latestWithdrawCandidate(Long userWorldId, Long characterId) {
        List<UserChatHistory> recentUserMarkers = lambdaQuery()
                .select(UserChatHistory::getId, UserChatHistory::getType)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue())
                        .or()
                        .eq(UserChatHistory::getType, ChatConstant.WITHDRAWN_TYPE))
                .orderByDesc(UserChatHistory::getId)
                .last("limit " + (ChatConstant.MAX_CONSECUTIVE_WITHDRAW_COUNT + 1))
                .list();

        int consecutiveWithdrawCount = 0;
        for (UserChatHistory message : recentUserMarkers) {
            if (isWithdrawnMessage(message)) {
                consecutiveWithdrawCount++;
                continue;
            }
            return new WithdrawCandidate(message, consecutiveWithdrawCount);
        }
        return new WithdrawCandidate(null, consecutiveWithdrawCount);
    }

    private Set<Long> linkedHistoryIds(Long userWorldId, Long characterId, Long userMessageId) {
        return lambdaQuery()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .eq(UserChatHistory::getUserMessageId, userMessageId)
                .list()
                .stream()
                .map(UserChatHistory::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private void reverseFavorUpdates(Long userWorldId, Long characterId, Long userMessageId) {
        List<UserCharacterFavorLog> favorLogs = userCharacterFavorLogMapper.selectList(
                new LambdaQueryWrapper<UserCharacterFavorLog>()
                        .select(UserCharacterFavorLog::getFavorUpdate)
                        .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                        .eq(UserCharacterFavorLog::getCharacterId, characterId)
                        .eq(UserCharacterFavorLog::getBindingChat, userMessageId));
        if (favorLogs.isEmpty()) {
            return;
        }

        int totalFavorUpdate = favorLogs.stream()
                .map(UserCharacterFavorLog::getFavorUpdate)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        if (totalFavorUpdate != 0) {
            Integer favorValue = userCharacterInfoMapper.updateFavorValue(userWorldId, characterId, -totalFavorUpdate);
            if (favorValue == null) {
                throw new UserRequestException("角色不存在");
            }
            redisTemplate.opsForHash().put(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY,
                    buildFavorCacheKey(userWorldId, characterId), String.valueOf(favorValue));
            redisTemplate.delete(buildPromptInfoCacheKey(userWorldId, characterId));
        }

        userCharacterFavorLogMapper.delete(new LambdaUpdateWrapper<UserCharacterFavorLog>()
                .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                .eq(UserCharacterFavorLog::getCharacterId, characterId)
                .eq(UserCharacterFavorLog::getBindingChat, userMessageId));
    }

    private void deleteLinkedMessages(Long userWorldId, Long characterId, Long userMessageId) {
        userChatThinkingHistoryMapper.delete(new LambdaUpdateWrapper<UserChatThinkingHistory>()
                .eq(UserChatThinkingHistory::getUserMessageId, userMessageId));
        userChatToolCallMapper.delete(new LambdaUpdateWrapper<UserChatToolCall>()
                .eq(UserChatToolCall::getUserMessageId, userMessageId));
        baseMapper.delete(new LambdaUpdateWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .eq(UserChatHistory::getUserMessageId, userMessageId));
    }

    private void markUserMessageWithdrawn(Long userWorldId, Long characterId, Long userMessageId) {
        boolean updated = lambdaUpdate()
                .eq(UserChatHistory::getId, userMessageId)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue()))
                .set(UserChatHistory::getType, ChatConstant.WITHDRAWN_TYPE)
                .set(UserChatHistory::getContent, null)
                .update();
        if (!updated) {
            throw new UserRequestException("没有可撤回的用户消息");
        }
    }

    private void deleteAutoSearchInfoAfter(Long userWorldId, Long characterId, Long userMessageId) {
        Long nextUserMarkerId = nextUserMarkerIdAfter(userWorldId, characterId, userMessageId);
        baseMapper.delete(new LambdaUpdateWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .eq(UserChatHistory::getType, ChatConstant.AUTO_SEARCH_INFO_TYPE)
                .gt(UserChatHistory::getId, userMessageId)
                .lt(nextUserMarkerId != null, UserChatHistory::getId, nextUserMarkerId));
    }

    private Long nextUserMarkerIdAfter(Long userWorldId, Long characterId, Long userMessageId) {
        UserChatHistory nextUserMarker = lambdaQuery()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .gt(UserChatHistory::getId, userMessageId)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue())
                        .or()
                        .eq(UserChatHistory::getType, ChatConstant.WITHDRAWN_TYPE))
                .orderByAsc(UserChatHistory::getId)
                .last("limit 1")
                .one();
        return nextUserMarker == null ? null : nextUserMarker.getId();
    }

    private void deleteStepNoKey(Long userMessageId) {
        try {
            redisTemplate.delete(RedisConstant.CHAT_MEMORY_STEP_KEY_PREFIX + userMessageId);
        } catch (RuntimeException ignored) {
            // Redis 只是 stepNo 快路径；撤回缓存失败不影响数据库回滚语义。
        }
    }

    private void refreshLatestChatInfo(Long userWorldId, Long characterId) {
        UserChatHistory latestAssistant = latestAssistantMessage(userWorldId, characterId);
        userCharacterInfoMapper.update(new LambdaUpdateWrapper<UserCharacterInfo>()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .set(UserCharacterInfo::getLastChatTime,
                        latestAssistant == null ? null : latestAssistant.getTimestamp())
                .set(UserCharacterInfo::getLastChatContent,
                        latestAssistant == null ? null : latestAssistant.getContent()));

        String lastAssistantKey = buildLastAssistantKey(userWorldId, characterId);
        if (latestAssistant == null) {
            redisTemplate.delete(lastAssistantKey);
            return;
        }
        redisTemplate.opsForValue().set(lastAssistantKey, latestAssistant.getContent(),
                RedisConstant.LAST_ASSISTANT_TTL);
    }

    private List<UserChatHistory> listVisibleMessages(Long userWorldId, Long characterId,
                                                      List<UserChatHistory> primaryMessages) {
        Set<Long> userMessageIds = userMessageIds(primaryMessages);
        List<UserChatHistory> assistantMessages = listLinkedAssistantMessages(userWorldId, characterId, userMessageIds);
        List<UserChatHistory> messages = new ArrayList<>(primaryMessages.size() + assistantMessages.size());
        messages.addAll(primaryMessages);
        messages.addAll(assistantMessages);
        messages.sort(Comparator.comparing(UserChatHistory::getId));
        return messages;
    }

    private List<UserChatHistory> listThinkingMessages(Long userWorldId, Long characterId,
                                                       List<UserChatHistory> primaryMessages) {
        Set<Long> userMessageIds = userMessageIds(primaryMessages);
        Map<Long, List<UserChatHistory>> assistantsByUserMessageId =
                listLinkedAssistantMessages(userWorldId, characterId, userMessageIds).stream()
                        .collect(Collectors.groupingBy(UserChatHistory::getUserMessageId));
        Map<Long, List<UserChatThinkingHistory>> thinkingByUserMessageId = listThinking(userMessageIds);
        Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId = listToolCalls(userMessageIds);

        List<UserChatHistory> messages = new ArrayList<>();
        for (UserChatHistory primaryMessage : primaryMessages) {
            messages.add(primaryMessage);
            if (!isUserMessage(primaryMessage)) {
                continue;
            }
            Long userMessageId = primaryMessage.getId();
            for (Integer stepNo : stepNos(userMessageId, assistantsByUserMessageId,
                    thinkingByUserMessageId, toolCallsByUserMessageId)) {
                reasoningMessage(userMessageId, stepNo, thinkingByUserMessageId)
                        .ifPresent(messages::add);
                addToolCallPlaceholders(userMessageId, stepNo, toolCallsByUserMessageId, messages);
                assistantsByUserMessageId.getOrDefault(userMessageId, List.of())
                        .stream()
                        .filter(history -> Objects.equals(stepNo, history.getStepNo()))
                        .sorted(Comparator.comparing(UserChatHistory::getId))
                        .forEach(messages::add);
            }
        }
        return messages;
    }

    private List<UserChatHistory> listLinkedAssistantMessages(Long userWorldId, Long characterId,
                                                              Set<Long> userMessageIds) {
        if (userMessageIds.isEmpty()) {
            return List.of();
        }

        return lambdaQuery()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .eq(UserChatHistory::getType, MessageType.ASSISTANT.getValue())
                .in(UserChatHistory::getUserMessageId, userMessageIds)
                .orderByAsc(UserChatHistory::getId)
                .list();
    }

    private Map<Long, List<UserChatThinkingHistory>> listThinking(Set<Long> userMessageIds) {
        if (userMessageIds.isEmpty()) {
            return Map.of();
        }

        List<UserChatThinkingHistory> thinkingHistories = userChatThinkingHistoryMapper.selectList(
                new LambdaQueryWrapper<UserChatThinkingHistory>()
                        .in(UserChatThinkingHistory::getUserMessageId, userMessageIds)
                        .orderByAsc(UserChatThinkingHistory::getStepNo)
                        .orderByAsc(UserChatThinkingHistory::getId));
        return thinkingHistories.stream().collect(Collectors.groupingBy(UserChatThinkingHistory::getUserMessageId));
    }

    private Map<Long, List<UserChatToolCall>> listToolCalls(Set<Long> userMessageIds) {
        if (userMessageIds.isEmpty()) {
            return Map.of();
        }

        List<UserChatToolCall> toolCalls = userChatToolCallMapper.selectList(new LambdaQueryWrapper<UserChatToolCall>()
                .in(UserChatToolCall::getUserMessageId, userMessageIds)
                .orderByAsc(UserChatToolCall::getStepNo)
                .orderByAsc(UserChatToolCall::getId));
        return toolCalls.stream().collect(Collectors.groupingBy(UserChatToolCall::getUserMessageId));
    }

    private Set<Long> userMessageIds(List<UserChatHistory> userMessages) {
        return userMessages.stream()
                .filter(this::isUserMessage)
                .map(UserChatHistory::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean isUserMessage(UserChatHistory history) {
        return history.getType() == null || Objects.equals(MessageType.USER.getValue(), history.getType());
    }

    private boolean isWithdrawnMessage(UserChatHistory history) {
        return history != null && Objects.equals(ChatConstant.WITHDRAWN_TYPE, history.getType());
    }

    private UserChatHistory latestAssistantMessage(Long userWorldId, Long characterId) {
        return lambdaQuery()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .eq(UserChatHistory::getType, MessageType.ASSISTANT.getValue())
                .orderByDesc(UserChatHistory::getId)
                .last("limit 1")
                .one();
    }

    private String buildFavorCacheKey(Long userWorldId, Long characterId) {
        return userWorldId + ":" + characterId;
    }

    private String buildPromptInfoCacheKey(Long userWorldId, Long characterId) {
        return RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + userWorldId + ":" + characterId;
    }

    private String buildLastAssistantKey(Long userWorldId, Long characterId) {
        return RedisConstant.CHAT_KEY_PREFIX + userWorldId + ":" + characterId + RedisConstant.LAST_ASSISTANT_SUFFIX;
    }

    private List<Integer> stepNos(Long userMessageId,
                                  Map<Long, List<UserChatHistory>> assistantsByUserMessageId,
                                  Map<Long, List<UserChatThinkingHistory>> thinkingByUserMessageId,
                                  Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId) {
        Set<Integer> stepNos = new HashSet<>();
        assistantsByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .map(UserChatHistory::getStepNo)
                .filter(Objects::nonNull)
                .forEach(stepNos::add);
        thinkingByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .map(UserChatThinkingHistory::getStepNo)
                .filter(Objects::nonNull)
                .forEach(stepNos::add);
        toolCallsByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .map(UserChatToolCall::getStepNo)
                .filter(Objects::nonNull)
                .forEach(stepNos::add);
        return stepNos.stream().sorted().toList();
    }

    private Optional<UserChatHistory> reasoningMessage(Long userMessageId, Integer stepNo,
                                                       Map<Long, List<UserChatThinkingHistory>> thinkingByUserMessageId) {
        return thinkingByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .filter(thinking -> Objects.equals(stepNo, thinking.getStepNo()))
                .map(UserChatThinkingHistory::getReasoningContent)
                .filter(StringUtils::hasText)
                .findFirst()
                .map(content -> new UserChatHistory()
                        .setType(ChatConstant.THINKING_TYPE)
                        .setContent(content)
                        .setUserMessageId(userMessageId)
                        .setStepNo(stepNo));
    }

    private void addToolCallPlaceholders(Long userMessageId, Integer stepNo,
                                         Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId,
                                         List<UserChatHistory> messages) {
        toolCallsByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .filter(toolCall -> Objects.equals(stepNo, toolCall.getStepNo()))
                .forEach(toolCall -> messages.add(new UserChatHistory().setType(ChatConstant.TOOL_TYPE)));
    }

    private record WithdrawCandidate(UserChatHistory userMessage, int consecutiveWithdrawCount) {
    }
}
