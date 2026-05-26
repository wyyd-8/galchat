package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.DateTimeConstant;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.service.IUserEventLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserEventLogDetector {

    private static final int HISTORY_LIMIT = 20;

    private final ChatClient userEventLogClient;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final IUserEventLogService userEventLogService;

    public void detectAndSave(Long userWorldId, Long characterId, Long userMessageId) {
        if (userWorldId == null || characterId == null || userMessageId == null) {
            return;
        }

        List<UserChatHistory> histories = listHistories(userWorldId, characterId, userMessageId);
        if (histories.isEmpty()) {
            return;
        }

        detectAndSave(userWorldId, characterId, userMessageId, histories);
    }

    public void detectAndSave(Long userWorldId, Long characterId, Long userMessageId, List<UserChatHistory> histories) {
        if (userWorldId == null || characterId == null || userMessageId == null || histories == null) {
            return;
        }

        List<UserChatHistory> dialogHistories = histories.stream()
                .filter(this::isDialogHistory)
                .toList();
        if (dialogHistories.isEmpty()) {
            return;
        }

        String content = userEventLogClient.prompt()
                .user(formatPrompt(dialogHistories, userMessageId))
                .call()
                .content();
        UserEventLog userEventLog = parseUserEventLog(content, userWorldId, characterId);
        if (userEventLog == null) {
            return;
        }

        userEventLogService.addUserEventLog(userEventLog);
    }

    private List<UserChatHistory> listHistories(Long userWorldId, Long characterId, Long userMessageId) {
        List<UserChatHistory> histories = userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .le(UserChatHistory::getId, userMessageId)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .in(UserChatHistory::getType, List.of(MessageType.USER.getValue(),
                                MessageType.ASSISTANT.getValue())))
                .orderByDesc(UserChatHistory::getId)
                .last("limit " + HISTORY_LIMIT));
        Collections.reverse(histories);
        return histories;
    }

    private String formatPrompt(List<UserChatHistory> histories, Long userMessageId) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前窗口对话历史：\n");
        for (UserChatHistory history : histories) {
            builder.append(formatHistory(history, userMessageId)).append('\n');
        }
        return builder.toString();
    }

    private String formatHistory(UserChatHistory history, Long userMessageId) {
        String type = StringUtils.hasText(history.getType()) ? history.getType() : MessageType.USER.getValue();
        String timestamp = history.getTimestamp() == null
                ? "unknown"
                : history.getTimestamp().format(DateTimeConstant.DATE_TIME_FORMATTER);
        String currentMessageMark = Objects.equals(history.getId(), userMessageId) ? "（当前用户消息）" : "";
        return "[" + timestamp + "] " + type + currentMessageMark + ": " + history.getContent();
    }

    private boolean isDialogHistory(UserChatHistory history) {
        if (history == null) {
            return false;
        }
        String type = StringUtils.hasText(history.getType()) ? history.getType() : MessageType.USER.getValue();
        return MessageType.USER.getValue().equals(type) || MessageType.ASSISTANT.getValue().equals(type);
    }

    private UserEventLog parseUserEventLog(String content, Long userWorldId, Long characterId) {
        if (!StringUtils.hasText(content)) {
            return null;
        }

        try {
            JSONObject jsonObject = new JSONObject(normalizeJson(content));
            String eventDescription = jsonObject.optString("eventDescription", "");
            LocalDateTime eventTime = parseTime(jsonObject.optString("time", ""));
            if (!StringUtils.hasText(eventDescription) || eventTime == null) {
                return null;
            }
            return new UserEventLog()
                    .setUserWorldId(userWorldId)
                    .setCharacterId(characterId)
                    .setTime(eventTime)
                    .setEventDescription(eventDescription);
        } catch (JSONException e) {
            log.warn("用户事件判断结果不是有效JSON: {}", content);
            return null;
        }
    }

    private String normalizeJson(String content) {
        String trimmedContent = content.trim();
        int beginIndex = trimmedContent.indexOf('{');
        int endIndex = trimmedContent.lastIndexOf('}');
        if (beginIndex < 0 || endIndex < beginIndex) {
            return trimmedContent;
        }
        return trimmedContent.substring(beginIndex, endIndex + 1);
    }

    private LocalDateTime parseTime(String time) {
        if (!StringUtils.hasText(time)) {
            return null;
        }
        try {
            return LocalDateTime.parse(time);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
