package com.me.galchat.memory;

import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import org.json.JSONObject;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TopicBoundaryService {

    private static final String KEY_PREFIX = "chat:topic:boundary:";
    private static final String PREVIOUS_START_ID = "previousStartId";
    private static final String CURRENT_START_ID = "currentStartId";
    private static final String LAST_CHECKED_MESSAGE_ID = "lastCheckedMessageId";
    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StringRedisTemplate redisTemplate;
    private final ChatClient topicClient;
    private final UserChatHistoryChatMemory topicChatMemory;

    public TopicBoundaryService(StringRedisTemplate redisTemplate, ChatClient topicClient,
                                UserChatHistoryChatMemory topicChatMemory) {
        this.redisTemplate = redisTemplate;
        this.topicClient = topicClient;
        this.topicChatMemory = topicChatMemory;
    }

    public TopicBoundary updateAfterUserMessage(ConversationInfo baseConversation, UserChatHistory userMessage) {
        TopicBoundary oldBoundary = getOrCreateBoundary(baseConversation, userMessage.getId());
        ConversationInfo topicConversation = new ConversationInfo(baseConversation.getUserWorldId(),
                baseConversation.getCharacterId(), oldBoundary.windowStartId());

        boolean sameTopic = isSameTopic(topicConversation, userMessage);
        TopicBoundary newBoundary = sameTopic
                ? new TopicBoundary(oldBoundary.previousStartId(), oldBoundary.currentStartId(), userMessage.getId())
                : new TopicBoundary(oldBoundary.currentStartId(), userMessage.getId(), userMessage.getId());

        saveBoundary(baseConversation, newBoundary);
        return newBoundary;
    }

    public TopicBoundary getBoundary(ConversationInfo conversationInfo) {
        String value = redisTemplate.opsForValue().get(buildKey(conversationInfo));
        if (!StringUtils.hasText(value)) {
            return new TopicBoundary(conversationInfo.getStart(), conversationInfo.getStart(), null);
        }

        JSONObject jsonObject = new JSONObject(value);
        return new TopicBoundary(
                readLong(jsonObject, PREVIOUS_START_ID),
                readLong(jsonObject, CURRENT_START_ID),
                readLong(jsonObject, LAST_CHECKED_MESSAGE_ID)
        );
    }

    private TopicBoundary getOrCreateBoundary(ConversationInfo conversationInfo, Long fallbackStartId) {
        TopicBoundary boundary = getBoundary(conversationInfo);
        Long currentStartId = boundary.currentStartId();
        if (currentStartId != null) {
            return boundary;
        }

        Long startId = conversationInfo.getStart() != null ? conversationInfo.getStart() : fallbackStartId;
        TopicBoundary initialBoundary = new TopicBoundary(startId, startId, boundary.lastCheckedMessageId());
        saveBoundary(conversationInfo, initialBoundary);
        return initialBoundary;
    }

    private boolean isSameTopic(ConversationInfo topicConversation, UserChatHistory userMessage) {
        List<UserChatHistory> history = new ArrayList<>(topicChatMemory.listHistories(topicConversation));
        if (history.size() <= 1) {
            return true;
        }
        history.removeLast();

        String prompt = """
                你是一个专业的对话概要机器人，能够判断当前对话与上一段对话是否连续且为同一话题
                每个对话均包含对话人，时间戳与对话内容
                以下是历史对话与当前用户消息
                现在，你需要判断两段对话是否为连续且为同一话题，是输出"true"，不是或无法判断输出"false"

                历史对话：
                %s

                当前用户消息：
                %s
                """.formatted(formatMessages(history), formatMessage(userMessage));

        String content = topicClient.prompt()
                .user(prompt)
                .call()
                .content();

        return content != null && content.trim().equalsIgnoreCase("true");
    }

    private String formatMessages(List<UserChatHistory> messages) {
        StringBuilder builder = new StringBuilder();
        for (UserChatHistory message : messages) {
            builder.append(formatMessage(message)).append('\n');
        }
        return builder.toString();
    }

    private String formatMessage(UserChatHistory message) {
        return "[" + formatTimestamp(message.getTimestamp()) + "] "
                + formatType(message.getType()) + ": "
                + message.getContent();
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "unknown";
        }
        return MESSAGE_TIME_FORMATTER.format(timestamp);
    }

    private String formatType(String type) {
        if (!StringUtils.hasText(type)) {
            return "user";
        }
        return type;
    }

    private void saveBoundary(ConversationInfo conversationInfo, TopicBoundary boundary) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(PREVIOUS_START_ID, boundary.previousStartId());
        jsonObject.put(CURRENT_START_ID, boundary.currentStartId());
        jsonObject.put(LAST_CHECKED_MESSAGE_ID, boundary.lastCheckedMessageId());
        redisTemplate.opsForValue().set(buildKey(conversationInfo), jsonObject.toString());
    }

    private Long readLong(JSONObject jsonObject, String key) {
        if (!jsonObject.has(key) || jsonObject.isNull(key)) {
            return null;
        }
        return jsonObject.getLong(key);
    }

    private String buildKey(ConversationInfo conversationInfo) {
        return KEY_PREFIX + conversationInfo.getUserWorldId() + ":" + conversationInfo.getCharacterId();
    }
}
