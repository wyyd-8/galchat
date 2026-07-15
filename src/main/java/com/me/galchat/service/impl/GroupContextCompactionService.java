package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.context.GroupContextStrategy;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GroupContextCompactionService implements GroupContextStrategy {

    private final GroupChatMessageMapper messageMapper;
    private final GroupContextSummaryMapper summaryMapper;
    private final ChatClient summaryClient;

    public GroupContextCompactionService(GroupChatMessageMapper messageMapper,
                                         GroupContextSummaryMapper summaryMapper,
                                         @Qualifier("groupNonThinkingChatClient") ChatClient summaryClient) {
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.summaryClient = summaryClient;
    }

    @Override
    public boolean supports(String mode) {
        return GroupChatConstant.MODE_CHAT.equals(mode);
    }

    @Override
    public void compactIfNeeded(GroupConversation conversation) {
        GroupContextSummary previous = latestSummary(conversation.getId());
        long coveredSequence = previous == null ? 0L : previous.getEndSequence();
        List<GroupChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversation.getId())
                .gt(GroupChatMessage::getSequenceNo, coveredSequence)
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .orderByAsc(GroupChatMessage::getSequenceNo));
        if (messages.size() <= GroupChatConstant.CONTEXT_RECENT_MESSAGE_COUNT
                || contentLength(messages) <= GroupChatConstant.CONTEXT_COMPACT_TRIGGER_CHARS) {
            return;
        }

        int compactCount = messages.size() - GroupChatConstant.CONTEXT_RECENT_MESSAGE_COUNT;
        List<GroupChatMessage> compactedMessages = messages.subList(0, compactCount);
        GroupChatMessage lastCompacted = compactedMessages.getLast();
        String prompt = formatSummaryPrompt(previous, compactedMessages);
        String summary = summaryClient.prompt(new Prompt(List.of(
                        new SystemMessage("""
                                你负责压缩多人角色扮演群聊的公开上下文。
                                只保留已经发生且对后续有意义的事实、角色关系变化、未解决问题和场景状态。
                                必须保留发言者身份；不得把角色猜测写成确定事实；不得添加新事实。
                                不要输出分析过程，只输出简洁的中文概要。
                                """),
                        new UserMessage(prompt))))
                .call()
                .content();
        if (!StringUtils.hasText(summary)) {
            return;
        }

        summaryMapper.insert(new GroupContextSummary()
                .setConversationId(conversation.getId())
                .setStartSequence(compactedMessages.getFirst().getSequenceNo())
                .setEndSequence(lastCompacted.getSequenceNo())
                .setSummary(summary.trim())
                .setVersion(previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1)
                .setCreatedAt(LocalDateTime.now()));
    }

    @Override
    public GroupContextSummary latestSummary(Long conversationId) {
        return summaryMapper.selectOne(new LambdaQueryWrapper<GroupContextSummary>()
                .eq(GroupContextSummary::getConversationId, conversationId)
                .orderByDesc(GroupContextSummary::getEndSequence)
                .orderByDesc(GroupContextSummary::getId)
                .last("limit 1"));
    }

    private int contentLength(List<GroupChatMessage> messages) {
        return messages.stream()
                .map(GroupChatMessage::getContent)
                .filter(content -> content != null)
                .mapToInt(String::length)
                .sum();
    }

    private String formatSummaryPrompt(GroupContextSummary previous, List<GroupChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        if (previous != null && StringUtils.hasText(previous.getSummary())) {
            builder.append("已有概要：\n").append(previous.getSummary()).append("\n\n");
        }
        builder.append("需要并入概要的新消息：\n");
        for (GroupChatMessage message : messages) {
            builder.append('[').append(message.getSpeakerType());
            if (message.getSpeakerId() != null) {
                builder.append(':').append(message.getSpeakerId());
            }
            builder.append("] ").append(message.getContent()).append('\n');
        }
        return builder.toString();
    }
}
