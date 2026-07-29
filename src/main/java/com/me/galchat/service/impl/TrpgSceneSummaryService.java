package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
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
public class TrpgSceneSummaryService {

    private final GroupChatMessageMapper messageMapper;
    private final GroupContextSummaryMapper summaryMapper;
    private final ChatClient summaryClient;

    public TrpgSceneSummaryService(
            GroupChatMessageMapper messageMapper,
            GroupContextSummaryMapper summaryMapper,
            @Qualifier("groupNonThinkingChatClient")
            ChatClient summaryClient) {
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.summaryClient = summaryClient;
    }

    public GroupContextSummary summarize(
            Long conversationId, Long sceneId) {
        GroupContextSummary previous = summaryMapper.selectOne(
                new LambdaQueryWrapper<GroupContextSummary>()
                        .eq(GroupContextSummary::getConversationId,
                                conversationId)
                        .eq(GroupContextSummary::getSceneId, sceneId)
                        .orderByDesc(GroupContextSummary::getEndSequence)
                        .last("limit 1"));
        long afterSequence = previous == null
                ? 0L : previous.getEndSequence();
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId,
                                conversationId)
                        .eq(GroupChatMessage::getSceneId, sceneId)
                        .gt(GroupChatMessage::getSequenceNo, afterSequence)
                        .eq(GroupChatMessage::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .eq(GroupChatMessage::getVisibility, "public")
                        .orderByAsc(GroupChatMessage::getSequenceNo));
        if (messages.isEmpty()) {
            return null;
        }
        StringBuilder history = new StringBuilder();
        for (GroupChatMessage message : messages) {
            history.append('[').append(message.getSpeakerType())
                    .append("] ").append(message.getContent())
                    .append('\n');
        }
        String summary = summaryClient.prompt(new Prompt(List.of(
                        new SystemMessage("""
                                你负责总结刚结束的一段COC场景探索。
                                只记录已发生的重要行动、公开发现、已展示材料、人物状态变化和未解决问题。
                                不得加入模组隐藏真相或尚未公开的信息，不输出标题和分析过程。
                                """),
                        new UserMessage(history.toString()))))
                .call()
                .content();
        if (!StringUtils.hasText(summary)) {
            summary = "本场景探索已结束。";
        }
        GroupContextSummary result = new GroupContextSummary()
                .setConversationId(conversationId)
                .setSceneId(sceneId)
                .setStartSequence(messages.getFirst().getSequenceNo())
                .setEndSequence(messages.getLast().getSequenceNo())
                .setSummary(summary.trim())
                .setVersion(previous == null
                        ? 1 : (previous.getVersion() == null
                                ? 2 : previous.getVersion() + 1))
                .setCreatedAt(LocalDateTime.now());
        summaryMapper.insert(result);
        return result;
    }
}
