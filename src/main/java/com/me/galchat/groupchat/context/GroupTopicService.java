package com.me.galchat.groupchat.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTopic;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTopicMapper;
import com.me.galchat.memory.TopicWindowPolicy;
import com.me.galchat.vector.GroupTopicVectorService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

@Service
public class GroupTopicService {

    private static final TopicWindowPolicy WINDOW_POLICY =
            new TopicWindowPolicy(GroupChatConstant.CONTEXT_TOPIC_COUNT,
                    GroupChatConstant.MAX_CONSECUTIVE_WITHDRAW_COUNT);

    private final GroupChatTopicMapper topicMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupTopicClassifier classifier;
    private final GroupTopicVectorService vectorService;

    public GroupTopicService(GroupChatTopicMapper topicMapper,
                             GroupChatMessageMapper messageMapper,
                             GroupTopicClassifier classifier,
                             GroupTopicVectorService vectorService) {
        this.topicMapper = topicMapper;
        this.messageMapper = messageMapper;
        this.classifier = classifier;
        this.vectorService = vectorService;
    }

    public void onTurnStarted(GroupConversation conversation, GroupChatMessage userMessage) {
        List<GroupChatTopic> topics = recentTopics(conversation.getId());
        if (topics.isEmpty()) {
            insertTopic(conversation.getId(), userMessage.getSequenceNo(), GroupChatConstant.TOPIC_BOUNDARY_SEMANTIC);
            return;
        }

        GroupChatTopic current = topics.getFirst();
        List<GroupChatMessage> currentMessages = messages(
                conversation.getId(), current.getStartSequence(), userMessage.getSequenceNo());
        boolean capacityReached = contentLength(currentMessages) + contentLength(userMessage)
                > GroupChatConstant.MAX_GROUP_TOPIC_CHARS;
        boolean sameTopic = !capacityReached && isSameTopic(currentMessages, userMessage);
        if (sameTopic) {
            return;
        }

        if (topics.size() > 1) {
            GroupChatTopic previous = topics.get(1);
            vectorService.addTopic(conversation, previous, current.getStartSequence());
        }
        insertTopic(conversation.getId(), userMessage.getSequenceNo(), capacityReached
                ? GroupChatConstant.TOPIC_BOUNDARY_CAPACITY
                : GroupChatConstant.TOPIC_BOUNDARY_SEMANTIC);
    }

    public long windowStartSequence(GroupConversation conversation) {
        List<GroupChatTopic> topics = recentTopics(conversation.getId());
        if (topics.isEmpty()) {
            return 1L;
        }
        return topics.size() > 1 ? topics.get(1).getStartSequence() : topics.getFirst().getStartSequence();
    }

    public void rollbackTurnBoundary(GroupConversation conversation, Long triggerSequence) {
        List<GroupChatTopic> topics = recentTopics(conversation.getId());
        if (topics.isEmpty() || !topics.getFirst().getStartSequence().equals(triggerSequence)) {
            return;
        }
        topicMapper.deleteById(topics.getFirst().getId());
        List<Long> remainingStarts = new ArrayList<>(topics.subList(1, topics.size()).stream()
                .map(GroupChatTopic::getStartSequence)
                .toList());
        Collections.reverse(remainingStarts);
        WINDOW_POLICY.intervalToDeleteAfterPop(remainingStarts)
                .ifPresent(interval -> vectorService.deleteTopic(
                        conversation.getId(), interval.start(), interval.end()));
    }

    public void flushOpenTopics(GroupConversation conversation) {
        List<GroupChatTopic> topics = recentTopics(conversation.getId());
        if (topics.isEmpty()) {
            return;
        }
        GroupChatMessage lastMessage = messageMapper.selectOne(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversation.getId())
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .eq(GroupChatMessage::getVisibility, "public")
                .orderByDesc(GroupChatMessage::getSequenceNo)
                .last("limit 1"));
        if (lastMessage == null || lastMessage.getSequenceNo() == null) {
            return;
        }
        GroupChatTopic current = topics.getFirst();
        if (topics.size() > 1) {
            vectorService.addTopic(conversation, topics.get(1), current.getStartSequence());
        }
        vectorService.addTopic(conversation, current, lastMessage.getSequenceNo() + 1);
    }

    private boolean isSameTopic(List<GroupChatMessage> messages, GroupChatMessage userMessage) {
        if (messages.isEmpty()) {
            return true;
        }
        try {
            return classifier.isSameTopic(messages, userMessage);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private List<GroupChatTopic> recentTopics(Long conversationId) {
        return topicMapper.selectList(new LambdaQueryWrapper<GroupChatTopic>()
                .eq(GroupChatTopic::getConversationId, conversationId)
                .orderByDesc(GroupChatTopic::getStartSequence)
                .orderByDesc(GroupChatTopic::getId)
                .last("limit " + WINDOW_POLICY.retainedTopicCount()));
    }

    private List<GroupChatMessage> messages(Long conversationId, Long startSequence, Long endSequence) {
        return messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversationId)
                .ge(GroupChatMessage::getSequenceNo, startSequence)
                .lt(GroupChatMessage::getSequenceNo, endSequence)
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .eq(GroupChatMessage::getVisibility, "public")
                .orderByAsc(GroupChatMessage::getSequenceNo));
    }

    private int contentLength(List<GroupChatMessage> messages) {
        return messages.stream().mapToInt(this::contentLength).sum();
    }

    private int contentLength(GroupChatMessage message) {
        return message.getContent() == null ? 0 : message.getContent().length();
    }

    private void insertTopic(Long conversationId, Long startSequence, String reason) {
        topicMapper.insert(new GroupChatTopic()
                .setConversationId(conversationId)
                .setStartSequence(startSequence)
                .setBoundaryReason(reason)
                .setCreatedAt(LocalDateTime.now()));
    }
}
