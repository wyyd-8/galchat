package com.me.galchat.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.DateTimeConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTopic;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTopicMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicBoundary;
import com.me.galchat.memory.TopicBoundaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RecentChatMemoryService {

    private final UserChatHistoryMapper historyMapper;
    private final TopicBoundaryService boundaryService;
    private final GroupConversationMapper conversationMapper;
    private final GroupChatTopicMapper topicMapper;
    private final GroupChatMessageMapper messageMapper;

    public List<Document> queryRecentMemories(Long userWorldId, Long characterId) {
        if (userWorldId == null || characterId == null) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        documents.addAll(singleChatTopics(userWorldId, characterId));
        for (GroupConversation conversation
                : conversationMapper.selectActiveChatByCharacter(userWorldId, characterId)) {
            documents.addAll(groupChatTopics(conversation));
        }
        return documents;
    }

    private List<Document> singleChatTopics(Long userWorldId, Long characterId) {
        TopicBoundary boundary = boundaryService.getBoundary(
                new ConversationInfo(userWorldId, characterId, null));
        List<Long> starts = newest(boundary.startIds(), ChatConstant.CONTEXT_TOPIC_COUNT);
        if (starts.isEmpty()) {
            return List.of();
        }

        List<UserChatHistory> histories = new ArrayList<>(historyMapper.selectList(
                new LambdaQueryWrapper<UserChatHistory>()
                        .eq(UserChatHistory::getUserWorldId, userWorldId)
                        .eq(UserChatHistory::getCharacterId, characterId)
                        .ge(UserChatHistory::getId, starts.getFirst())
                        .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                                .or()
                                .notIn(UserChatHistory::getType, List.of(
                                        ChatConstant.SYSTEM_TYPE,
                                        ChatConstant.TOOL_TYPE,
                                        ChatConstant.AUTO_SEARCH_INFO_TYPE,
                                        ChatConstant.WITHDRAWN_TYPE)))
                        .orderByAsc(UserChatHistory::getId)));
        histories.sort(Comparator.comparing(UserChatHistory::getId));

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            Long start = starts.get(i);
            Long nextStart = i + 1 < starts.size() ? starts.get(i + 1) : null;
            List<UserChatHistory> topic = histories.stream()
                    .filter(history -> history.getId() != null && history.getId() >= start)
                    .filter(history -> nextStart == null || history.getId() < nextStart)
                    .filter(history -> StringUtils.hasText(history.getContent()))
                    .toList();
            if (topic.isEmpty()) {
                continue;
            }
            Long end = nextStart == null ? increment(topic.getLast().getId()) : nextStart;
            documents.add(singleDocument(userWorldId, characterId, start, end, topic));
        }
        return documents;
    }

    private List<Document> groupChatTopics(GroupConversation conversation) {
        List<GroupChatTopic> newestTopics = topicMapper.selectList(
                        new LambdaQueryWrapper<GroupChatTopic>()
                                .eq(GroupChatTopic::getConversationId, conversation.getId())
                                .orderByDesc(GroupChatTopic::getStartSequence)
                                .orderByDesc(GroupChatTopic::getId)
                                .last("limit " + GroupChatConstant.CONTEXT_TOPIC_COUNT))
                .stream()
                .limit(GroupChatConstant.CONTEXT_TOPIC_COUNT)
                .sorted(Comparator.comparing(GroupChatTopic::getStartSequence))
                .toList();
        if (newestTopics.isEmpty()) {
            return List.of();
        }

        List<GroupChatMessage> messages = new ArrayList<>(messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId, conversation.getId())
                        .ge(GroupChatMessage::getSequenceNo, newestTopics.getFirst().getStartSequence())
                        .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                        .eq(GroupChatMessage::getVisibility, "public")
                        .orderByAsc(GroupChatMessage::getSequenceNo)
                        .orderByAsc(GroupChatMessage::getId)));
        messages.sort(Comparator.comparing(GroupChatMessage::getSequenceNo)
                .thenComparing(GroupChatMessage::getId));

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < newestTopics.size(); i++) {
            GroupChatTopic topicRow = newestTopics.get(i);
            Long start = topicRow.getStartSequence();
            Long nextStart = i + 1 < newestTopics.size()
                    ? newestTopics.get(i + 1).getStartSequence() : null;
            List<GroupChatMessage> topicMessages = messages.stream()
                    .filter(message -> message.getSequenceNo() != null && message.getSequenceNo() >= start)
                    .filter(message -> nextStart == null || message.getSequenceNo() < nextStart)
                    .filter(message -> StringUtils.hasText(message.getContent()))
                    .toList();
            if (topicMessages.isEmpty()) {
                continue;
            }
            Long end = nextStart == null
                    ? increment(topicMessages.getLast().getSequenceNo()) : nextStart;
            documents.add(groupDocument(conversation, topicRow, start, end, topicMessages));
        }
        return documents;
    }

    private Document singleDocument(Long userWorldId, Long characterId, Long start, Long end,
                                    List<UserChatHistory> histories) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(VectorConstant.SOURCE_METADATA_KEY, VectorConstant.CHAT_HISTORY_SOURCE);
        metadata.put(VectorConstant.USER_WORLD_ID_METADATA_KEY, userWorldId);
        metadata.put(VectorConstant.CHARACTER_ID_METADATA_KEY, characterId);
        metadata.put(VectorConstant.START_MESSAGE_ID_METADATA_KEY, start);
        metadata.put(VectorConstant.END_MESSAGE_ID_METADATA_KEY, end);
        putTimestamp(metadata, histories.getFirst().getTimestamp());
        return Document.builder()
                .id("recent-chat:%d:%d:%d:%d".formatted(userWorldId, characterId, start, end))
                .text(formatSingle(histories))
                .metadata(metadata)
                .build();
    }

    private Document groupDocument(GroupConversation conversation, GroupChatTopic topic, Long start, Long end,
                                   List<GroupChatMessage> messages) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(VectorConstant.SOURCE_METADATA_KEY, VectorConstant.GROUP_TOPIC_SOURCE);
        metadata.put(VectorConstant.USER_WORLD_ID_METADATA_KEY, conversation.getUserWorldId());
        metadata.put(VectorConstant.CONVERSATION_ID_METADATA_KEY, conversation.getId());
        metadata.put(VectorConstant.GROUP_TOPIC_ID_METADATA_KEY, topic.getId());
        metadata.put(VectorConstant.START_SEQUENCE_METADATA_KEY, start);
        metadata.put(VectorConstant.END_SEQUENCE_METADATA_KEY, end);
        putTimestamp(metadata, messages.getFirst().getCreatedAt());
        return Document.builder()
                .id("recent-group:%d:%d:%d".formatted(conversation.getId(), start, end))
                .text(formatGroup(messages))
                .metadata(metadata)
                .build();
    }

    private String formatSingle(List<UserChatHistory> histories) {
        StringBuilder builder = new StringBuilder();
        for (UserChatHistory history : histories) {
            String type = StringUtils.hasText(history.getType()) ? history.getType() : "user";
            builder.append('[').append(type).append("] ")
                    .append(history.getContent()).append('\n');
        }
        return builder.toString().trim();
    }

    private String formatGroup(List<GroupChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (GroupChatMessage message : messages) {
            builder.append('[').append(message.getSpeakerType());
            if (message.getSpeakerId() != null) {
                builder.append(':').append(message.getSpeakerId());
            }
            builder.append("] ").append(message.getContent()).append('\n');
        }
        return builder.toString().trim();
    }

    private List<Long> newest(List<Long> starts, int count) {
        if (starts == null || starts.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, starts.size() - count);
        return List.copyOf(starts.subList(from, starts.size()));
    }

    private Long increment(Long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private void putTimestamp(Map<String, Object> metadata, LocalDateTime timestamp) {
        if (timestamp != null) {
            metadata.put(VectorConstant.TIMESTAMP_METADATA_KEY,
                    timestamp.format(DateTimeConstant.DATE_TIME_FORMATTER));
        }
    }
}
