package com.me.galchat.memory;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.DateTimeConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.vector.ChatHistoryVectorService;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.json.JSONArray;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Service
public class TopicBoundaryService {

    private static final TopicWindowPolicy WINDOW_POLICY =
            new TopicWindowPolicy(ChatConstant.CONTEXT_TOPIC_COUNT,
                    ChatConstant.MAX_CONSECUTIVE_WITHDRAW_COUNT);

    private final StringRedisTemplate redisTemplate;
    private final ChatClient topicClient;
    private final UserChatMemory topicChatMemory;
    private final ChatHistoryVectorService chatHistoryVectorService;

    public TopicBoundary updateAfterUserMessage(ConversationInfo baseConversation, UserChatHistory userMessage) {
        TopicBoundary oldBoundary = getOrCreateBoundary(baseConversation, userMessage.getId());
        ConversationInfo topicConversation = new ConversationInfo(baseConversation.getUserWorldId(),
                baseConversation.getCharacterId(), WINDOW_POLICY.contextStart(oldBoundary.startIds()));

        boolean sameTopic = isSameTopic(topicConversation, userMessage);
        List<Long> starts = new ArrayList<>(oldBoundary.startIds());
        if (!sameTopic) {
            starts.add(userMessage.getId());
            WINDOW_POLICY.intervalToArchiveAfterAppend(starts)
                    .ifPresent(interval -> vectorizeChatHistory(userMessage.getUserWorldId(),
                            userMessage.getCharacterId(), interval.start(), interval.end()));
            starts = new ArrayList<>(WINDOW_POLICY.retain(starts));
        }
        TopicBoundary newBoundary = new TopicBoundary(starts, userMessage.getId());

        saveBoundary(baseConversation, newBoundary);
        return newBoundary;
    }

    public void startAssistantMessageTopic(UserChatHistory assistantMessage) {
        if (assistantMessage == null || assistantMessage.getUserWorldId() == null
                || assistantMessage.getCharacterId() == null || assistantMessage.getId() == null) {
            return;
        }

        ConversationInfo conversationInfo = new ConversationInfo(assistantMessage.getUserWorldId(),
                assistantMessage.getCharacterId(), null);
        TopicBoundary oldBoundary = getOrCreateBoundary(conversationInfo, assistantMessage.getId());
        List<Long> starts = new ArrayList<>(oldBoundary.startIds());
        if (!assistantMessage.getId().equals(oldBoundary.currentStartId())) {
            starts.add(assistantMessage.getId());
        }
        WINDOW_POLICY.intervalToArchiveAfterAppend(starts)
                .ifPresent(interval -> vectorizeChatHistory(assistantMessage.getUserWorldId(),
                        assistantMessage.getCharacterId(), interval.start(), interval.end()));
        TopicBoundary newBoundary =
                new TopicBoundary(WINDOW_POLICY.retain(starts), assistantMessage.getId());
        saveBoundary(conversationInfo, newBoundary);
    }

    public void rollbackAfterWithdraw(Long userWorldId, Long characterId, Set<Long> messageIds,
                                      Long lastRemainingMessageId) {
        if (userWorldId == null || characterId == null || messageIds == null || messageIds.isEmpty()) {
            return;
        }

        ConversationInfo conversationInfo = new ConversationInfo(userWorldId, characterId, null);
        TopicBoundary boundary = getBoundary(conversationInfo);
        List<Long> starts = new ArrayList<>(boundary.startIds());
        while (!starts.isEmpty() && messageIds.contains(starts.getLast())) {
            starts.removeLast();
            WINDOW_POLICY.intervalToDeleteAfterPop(starts)
                    .ifPresent(interval -> chatHistoryVectorService.deleteChatHistory(
                            userWorldId, characterId, interval.start(), interval.end()));
        }
        if (starts.isEmpty() && lastRemainingMessageId == null) {
            redisTemplate.delete(buildKey(conversationInfo));
        } else {
            saveBoundary(conversationInfo, new TopicBoundary(starts, lastRemainingMessageId));
        }
    }

    private void vectorizeChatHistory(Long userWorldId, Long characterId, Long startId, Long endId) {
        if (startId == null || endId == null || startId >= endId) {
            return;
        }

        chatHistoryVectorService.addChatHistory(userWorldId, characterId, startId, endId);
    }

    public TopicBoundary getBoundary(ConversationInfo conversationInfo) {
        String value = redisTemplate.opsForValue().get(buildKey(conversationInfo));
        if (!StringUtils.hasText(value)) {
            return new TopicBoundary(conversationInfo.getStart() == null
                    ? List.of() : List.of(conversationInfo.getStart()), null);
        }

        JSONObject jsonObject = new JSONObject(value);
        List<Long> starts = new ArrayList<>();
        JSONArray array = jsonObject.optJSONArray(ChatConstant.TOPIC_START_IDS_KEY);
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                starts.add(array.getLong(i));
            }
        }
        return new TopicBoundary(starts,
                readLong(jsonObject, ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY));
    }

    private TopicBoundary getOrCreateBoundary(ConversationInfo conversationInfo, Long fallbackStartId) {
        TopicBoundary boundary = getBoundary(conversationInfo);
        if (boundary.currentStartId() != null) {
            return boundary;
        }

        Long startId = conversationInfo.getStart() != null ? conversationInfo.getStart() : fallbackStartId;
        TopicBoundary initialBoundary = new TopicBoundary(
                startId == null ? List.of() : List.of(startId), boundary.lastCheckedMessageId());
        saveBoundary(conversationInfo, initialBoundary);
        return initialBoundary;
    }

    private boolean isSameTopic(ConversationInfo topicConversation, UserChatHistory userMessage) {
        List<UserChatHistory> history = new ArrayList<>(topicChatMemory.listHistories(topicConversation));
        if (history.size() <= 1) {
            return true;
        }
        history.removeLast();
        if (conversationContentLength(history, userMessage) > ChatConstant.MAX_TOPIC_CONVERSATION_LENGTH) {
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
        return DateTimeConstant.DATE_TIME_FORMATTER.format(timestamp);
    }

    private String formatType(String type) {
        if (!StringUtils.hasText(type)) {
            return "user";
        }
        return type;
    }

    private void saveBoundary(ConversationInfo conversationInfo, TopicBoundary boundary) {
        JSONObject jsonObject = new JSONObject();
        JSONArray starts = new JSONArray();
        boundary.startIds().forEach(starts::put);
        jsonObject.put(ChatConstant.TOPIC_START_IDS_KEY, starts);
        jsonObject.put(ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY, boundary.lastCheckedMessageId());
        redisTemplate.opsForValue().set(buildKey(conversationInfo), jsonObject.toString());
    }

    private Long readLong(JSONObject jsonObject, String key) {
        if (!jsonObject.has(key) || jsonObject.isNull(key)) {
            return null;
        }
        return jsonObject.getLong(key);
    }

    private String buildKey(ConversationInfo conversationInfo) {
        return RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX
                + conversationInfo.getUserWorldId() + ":" + conversationInfo.getCharacterId();
    }

}
