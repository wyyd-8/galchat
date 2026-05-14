package com.me.galchat.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.UserChatHistoryMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class UserChatHistoryChatMemory implements ChatMemory {

    private static final int DEFAULT_MAX_MESSAGES = 50;
    private static final String TOOL_CALL_TYPE = "tool_call";

    private final UserChatHistoryMapper userChatHistoryMapper;
    private final boolean includeToolCalls;
    private final boolean readOnly;
    private final int maxMessages;

    private UserChatHistoryChatMemory(Builder builder) {
        Assert.notNull(builder.userChatHistoryMapper, "userChatHistoryMapper cannot be null");
        Assert.isTrue(builder.maxMessages > 0, "maxMessages must be greater than 0");
        this.userChatHistoryMapper = builder.userChatHistoryMapper;
        this.includeToolCalls = builder.includeToolCalls;
        this.readOnly = builder.readOnly;
        this.maxMessages = builder.maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (readOnly) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }

        ConversationInfo conversationInfo = new ConversationInfo(conversationId);
        for (Message message : messages) {
            save(conversationInfo, message);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        ConversationInfo conversationInfo = new ConversationInfo(conversationId);
        return get(conversationInfo);
    }

    public List<Message> get(ConversationInfo conversationInfo) {
        List<UserChatHistory> histories = listHistories(conversationInfo);

        List<Message> messages = new ArrayList<>();
        histories.stream()
                .map(this::toMessage)
                .forEach(messages::add);

        return messages;
    }

    public List<UserChatHistory> listHistories(ConversationInfo conversationInfo) {
        LambdaQueryWrapper<UserChatHistory> queryWrapper = new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, conversationInfo.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, conversationInfo.getCharacterId())
                .ge(conversationInfo.getStart() != null, UserChatHistory::getId, conversationInfo.getStart())
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .notIn(UserChatHistory::getType, excludedTypes()))
                .orderByDesc(UserChatHistory::getId)
                .last("limit " + maxMessages);
        List<UserChatHistory> histories = userChatHistoryMapper.selectList(queryWrapper);
        Collections.reverse(histories);
        return histories;
    }

    public UserChatHistory save(ConversationInfo conversationInfo, Message message) {
        Assert.isTrue(!readOnly, "read only chat memory cannot save messages");
        UserChatHistory userChatHistory = toUserChatHistory(conversationInfo, message);
        userChatHistoryMapper.insert(userChatHistory);
        return userChatHistory;
    }

    public void deleteToolCallsBefore(ConversationInfo conversationInfo) {
        if (readOnly || conversationInfo.getStart() == null) {
            return;
        }

        userChatHistoryMapper.delete(new LambdaUpdateWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, conversationInfo.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, conversationInfo.getCharacterId())
                .lt(UserChatHistory::getId, conversationInfo.getStart())
                .in(UserChatHistory::getType, MessageType.TOOL.getValue(), TOOL_CALL_TYPE));
    }

    @Override
    public void clear(String conversationId) {
        if (readOnly) {
            return;
        }
        ConversationInfo conversationInfo = new ConversationInfo(conversationId);
        userChatHistoryMapper.delete(new LambdaUpdateWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, conversationInfo.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, conversationInfo.getCharacterId()));
    }

    private UserChatHistory toUserChatHistory(ConversationInfo conversationInfo, Message message) {
        return new UserChatHistory()
                .setUserWorldId(conversationInfo.getUserWorldId())
                .setCharacterId(conversationInfo.getCharacterId())
                .setContent(message.getText())
                .setType(getMessageType(message))
                .setTimestamp(LocalDateTime.now());
    }

    private String getMessageType(Message message) {
        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            return TOOL_CALL_TYPE;
        }
        return message.getMessageType().getValue();
    }

    private List<String> excludedTypes() {
        if (includeToolCalls) {
            return List.of(MessageType.SYSTEM.getValue());
        }
        return List.of(MessageType.SYSTEM.getValue(), MessageType.TOOL.getValue(), TOOL_CALL_TYPE);
    }

    private Message toMessage(UserChatHistory history) {
        String content = history.getContent();
        String type = normalizeType(history.getType());

        if (MessageType.SYSTEM.getValue().equals(type)) {
            return new SystemMessage(content);
        }
        if (MessageType.ASSISTANT.getValue().equals(type) || TOOL_CALL_TYPE.equals(type)) {
            return new AssistantMessage(content);
        }
        if (MessageType.TOOL.getValue().equals(type)) {
            return ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("history", "history", content)))
                    .build();
        }
        return new UserMessage(content);
    }

    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return MessageType.USER.getValue();
        }
        return type.toLowerCase(Locale.ROOT);
    }

    public static Builder builder(UserChatHistoryMapper userChatHistoryMapper) {
        return new Builder(userChatHistoryMapper);
    }

    public static class Builder {

        private final UserChatHistoryMapper userChatHistoryMapper;
        private boolean includeToolCalls;
        private boolean readOnly;
        private int maxMessages = DEFAULT_MAX_MESSAGES;

        private Builder(UserChatHistoryMapper userChatHistoryMapper) {
            this.userChatHistoryMapper = userChatHistoryMapper;
        }

        public Builder includeToolCalls(boolean includeToolCalls) {
            this.includeToolCalls = includeToolCalls;
            return this;
        }

        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public UserChatHistoryChatMemory build() {
            return new UserChatHistoryChatMemory(this);
        }
    }
}
