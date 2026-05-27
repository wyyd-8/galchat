package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.service.IUserChatHistoryService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
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

    private List<UserChatHistory> listPrimaryMessages(Long userWorldId, Long characterId, Long id, Integer size) {
        List<UserChatHistory> primaryMessages = lambdaQuery()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .lt(id != null, UserChatHistory::getId, id)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue())
                        .or()
                        .in(UserChatHistory::getType, storyMessageTypes()))
                .orderByDesc(UserChatHistory::getId)
                .last("limit " + size)
                .list();
        Collections.reverse(primaryMessages);
        return primaryMessages;
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

    private List<String> storyMessageTypes() {
        return List.of(ChatConstant.STORY_START_TYPE, ChatConstant.STORY_PROGRESS_TYPE, ChatConstant.STORY_END_TYPE);
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
}
