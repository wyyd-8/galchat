package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.WorldEventLogMapper;
import com.me.galchat.service.IWorldEventLogService;
import com.me.galchat.vector.WorldEventVectorService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GroupConversationLifecycleService {

    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanService replyPlanService;
    private final GroupChatMessageMapper messageMapper;
    private final GroupContextSummaryMapper summaryMapper;
    private final IWorldEventLogService worldEventLogService;
    private final WorldEventLogMapper worldEventLogMapper;
    private final WorldEventVectorService worldEventVectorService;
    private final ChatClient summaryClient;
    private final TransactionTemplate transactionTemplate;

    public GroupConversationLifecycleService(GroupConversationService conversationService,
                                             GroupConversationLockService lockService,
                                             GroupConversationMapper conversationMapper,
                                             GroupReplyPlanService replyPlanService,
                                             GroupChatMessageMapper messageMapper,
                                             GroupContextSummaryMapper summaryMapper,
                                             IWorldEventLogService worldEventLogService,
                                             WorldEventLogMapper worldEventLogMapper,
                                             WorldEventVectorService worldEventVectorService,
                                             @Qualifier("groupNonThinkingChatClient") ChatClient summaryClient,
                                             TransactionTemplate transactionTemplate) {
        this.conversationService = conversationService;
        this.lockService = lockService;
        this.conversationMapper = conversationMapper;
        this.replyPlanService = replyPlanService;
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.worldEventLogService = worldEventLogService;
        this.worldEventLogMapper = worldEventLogMapper;
        this.worldEventVectorService = worldEventVectorService;
        this.summaryClient = summaryClient;
        this.transactionTemplate = transactionTemplate;
    }

    public GroupConversation close(Long conversationId) {
        GroupConversation conversation = conversationService.requireAuthorized(conversationId);
        GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
        if (lock == null) {
            throw new UserRequestException("群聊正在生成回复，请稍后再结束");
        }
        try {
            List<GroupChatMessage> messages = completedMessages(conversationId);
            String summary = generateSummary(conversation, messages);
            GroupConversation result = transactionTemplate.execute(status ->
                    saveFinalSummary(conversation, messages, summary));
            saveWorldEvent(result, summary);
            return result;
        } finally {
            lockService.unlock(lock);
        }
    }

    private GroupConversation saveFinalSummary(GroupConversation conversation, List<GroupChatMessage> messages,
                                               String summary) {
        summaryMapper.delete(new LambdaQueryWrapper<GroupContextSummary>()
                .eq(GroupContextSummary::getConversationId, conversation.getId()));
        if (!messages.isEmpty()) {
            summaryMapper.insert(new GroupContextSummary()
                    .setConversationId(conversation.getId())
                    .setStartSequence(messages.getFirst().getSequenceNo())
                    .setEndSequence(messages.getLast().getSequenceNo())
                    .setSummary(summary)
                    .setVersion(1)
                    .setCreatedAt(LocalDateTime.now()));
        }
        LocalDateTime now = LocalDateTime.now();
        replyPlanService.clearConversationPlans(conversation);
        conversation.setSummary(summary)
                .setStatus(GroupChatConstant.STATUS_CLOSED)
                .setClosedAt(now)
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return conversation;
    }

    private void saveWorldEvent(GroupConversation conversation, String summary) {
        List<GroupChatMember> members = conversationService.listMembers(conversation.getId());
        WorldEventLog event = worldEventLogMapper.selectOne(new LambdaQueryWrapper<WorldEventLog>()
                .eq(WorldEventLog::getConversationId, conversation.getId())
                .last("limit 1"));
        boolean isNew = event == null;
        if (isNew) {
            event = new WorldEventLog();
        }
        event
                .setUserWorldId(conversation.getUserWorldId())
                .setConversationId(conversation.getId())
                .setTitle(conversation.getTitle())
                .setEventDescription(summary)
                .setVisibleCharacters(members.stream().map(GroupChatMember::getActorId).toArray(Long[]::new))
                .setTimestamp(conversation.getClosedAt());
        if (isNew) {
            worldEventLogService.save(event);
        } else {
            worldEventLogMapper.updateById(event);
        }
        worldEventVectorService.addWorldEventLog(event);
    }

    private List<GroupChatMessage> completedMessages(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversationId)
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .orderByAsc(GroupChatMessage::getSequenceNo));
    }

    private String generateSummary(GroupConversation conversation, List<GroupChatMessage> messages) {
        StringBuilder prompt = new StringBuilder();
        appendLine(prompt, "标题", conversation.getTitle());
        prompt.append("群聊记录：\n");
        for (GroupChatMessage message : messages) {
            prompt.append('[').append(message.getSpeakerType());
            if (message.getSpeakerId() != null) {
                prompt.append(':').append(message.getSpeakerId());
            }
            prompt.append("] ").append(message.getContent()).append('\n');
        }
        String summary = summaryClient.prompt(new Prompt(List.of(
                        new SystemMessage("""
                                你负责重写一场多人群聊的最终概要。
                                概要应包含起因、重要行动、结果、状态变化和仍未解决的问题，适合后续长期检索。
                                只能使用记录中已经出现的事实，不得把角色猜测写成事实，不得添加新事件。
                                不要输出分析过程、Markdown 标题或其他说明，只输出简洁完整的中文概要。
                                """),
                        new UserMessage(prompt.toString()))))
                .call()
                .content();
        if (StringUtils.hasText(summary)) {
            return summary.trim();
        }
        return conversation.getTitle();
    }

    private void appendLine(StringBuilder builder, String name, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(name).append('：').append(value.trim()).append('\n');
        }
    }
}
