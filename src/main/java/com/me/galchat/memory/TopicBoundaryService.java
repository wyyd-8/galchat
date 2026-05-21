package com.me.galchat.memory;

import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.tool.VectorTools;
import com.me.galchat.vector.ChatHistoryVectorService;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class TopicBoundaryService {

    private static final String KEY_PREFIX = "chat:topic:boundary:";
    private static final String PREVIOUS_START_ID = "previousStartId";
    private static final String CURRENT_START_ID = "currentStartId";
    private static final String LAST_CHECKED_MESSAGE_ID = "lastCheckedMessageId";
    private static final String AUTO_SEARCH_INFO_PREFIX = "自动调用searchInfo结果：\n";
    private static final int MAX_TOPIC_CONVERSATION_LENGTH = 10000;
    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StringRedisTemplate redisTemplate;
    private final ChatClient topicClient;
    private final UserChatHistoryChatMemory topicChatMemory;
    private final VectorTools vectorTools;
    private final ChatHistoryVectorService chatHistoryVectorService;

    public TopicBoundary updateAfterUserMessage(ConversationInfo baseConversation, UserChatHistory userMessage) {
        TopicBoundary oldBoundary = getOrCreateBoundary(baseConversation, userMessage.getId());
        ConversationInfo topicConversation = new ConversationInfo(baseConversation.getUserWorldId(),
                baseConversation.getCharacterId(), oldBoundary.windowStartId());

        boolean sameTopic = isSameTopic(topicConversation, userMessage);
        TopicBoundary newBoundary = sameTopic
                ? new TopicBoundary(oldBoundary.previousStartId(), oldBoundary.currentStartId(), userMessage.getId())
                : new TopicBoundary(oldBoundary.currentStartId(), userMessage.getId(), userMessage.getId());

        saveBoundary(baseConversation, newBoundary);
        if (!sameTopic) {
            searchInfoAfterFirstMessageInTopic(userMessage);
            chatHistoryVectorService.addChatHistory(userMessage.getUserWorldId(), userMessage.getCharacterId(),
                    oldBoundary.previousStartId(), oldBoundary.currentStartId());
        }
        return newBoundary;
    }

    private void searchInfoAfterFirstMessageInTopic(UserChatHistory message) {
        if (!StringUtils.hasText(message.getContent())) {
            return;
        }

        String searchInfo = vectorTools.searchInfo(message.getContent(), new ToolContext(Map.of(
                "userWorldId", message.getUserWorldId(),
                "characterId", message.getCharacterId()
        )));
        if (!StringUtils.hasText(searchInfo)) {
            return;
        }

        topicChatMemory.saveAutoSearchInfo(new ConversationInfo(message.getUserWorldId(), message.getCharacterId(), null),
                AUTO_SEARCH_INFO_PREFIX + searchInfo);
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
        if (conversationContentLength(history, userMessage) > MAX_TOPIC_CONVERSATION_LENGTH) {
            return false;
        }

        String prompt = """
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

    private int conversationContentLength(List<UserChatHistory> history, UserChatHistory userMessage) {
        int length = contentLength(userMessage);
        for (UserChatHistory message : history) {
            length += contentLength(message);
        }
        return length;
    }

    private int contentLength(UserChatHistory message) {
        String content = message.getContent();
        if (content == null) {
            return 0;
        }
        return content.length();
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
