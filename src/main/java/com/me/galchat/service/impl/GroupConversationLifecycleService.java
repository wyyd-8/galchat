package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.exception.UserRequestException;
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
    private final GroupContextSummaryMapper summaryMapper;
    private final IWorldEventLogService worldEventLogService;
    private final WorldEventLogMapper worldEventLogMapper;
    private final WorldEventVectorService worldEventVectorService;
    private final GroupTurnRecoveryService recoveryService;
    private final ChatClient summaryClient;
    private final TransactionTemplate transactionTemplate;
    private final TrpgSummaryIntervalSelector summaryIntervalSelector;

    public GroupConversationLifecycleService(GroupConversationService conversationService,
                                             GroupConversationLockService lockService,
                                             GroupConversationMapper conversationMapper,
                                             GroupReplyPlanService replyPlanService,
                                             GroupContextSummaryMapper summaryMapper,
                                             IWorldEventLogService worldEventLogService,
                                             WorldEventLogMapper worldEventLogMapper,
                                             WorldEventVectorService worldEventVectorService,
                                             GroupTurnRecoveryService recoveryService,
                                             @Qualifier("groupNonThinkingChatClient") ChatClient summaryClient,
                                             TransactionTemplate transactionTemplate,
                                             TrpgSummaryIntervalSelector summaryIntervalSelector) {
        this.conversationService = conversationService;
        this.lockService = lockService;
        this.conversationMapper = conversationMapper;
        this.replyPlanService = replyPlanService;
        this.summaryMapper = summaryMapper;
        this.worldEventLogService = worldEventLogService;
        this.worldEventLogMapper = worldEventLogMapper;
        this.worldEventVectorService = worldEventVectorService;
        this.recoveryService = recoveryService;
        this.summaryClient = summaryClient;
        this.transactionTemplate = transactionTemplate;
        this.summaryIntervalSelector = summaryIntervalSelector;
    }

    public GroupConversation close(Long conversationId) {
        conversationService.requireAuthorized(conversationId);
        GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
        if (lock == null) {
            throw new UserRequestException("群聊正在生成回复，请稍后再结束");
        }
        try {
            GroupConversation lockedConversation = conversationService.requireActive(conversationId);
            return closeUnderLock(lockedConversation);
        } finally {
            lockService.unlock(lock);
        }
    }

    /**
     * Closes a conversation while the caller already owns its conversation lock.
     */
    public GroupConversation closeUnderLock(
            GroupConversation lockedConversation) {
        requireActive(lockedConversation);
        Long conversationId = lockedConversation.getId();
        recoveryService.assertConversationHasNoNonTerminalTurns(
                conversationId);
        return closeValidated(lockedConversation);
    }

    public GroupConversation closeAfterTurnUnderLock(
            GroupConversation lockedConversation,
            Long completingTurnId) {
        requireActive(lockedConversation);
        if (completingTurnId == null) {
            throw new UserRequestException("结束跑团缺少当前轮次");
        }
        recoveryService.assertConversationHasNoNonTerminalTurnsExcept(
                lockedConversation.getId(), completingTurnId);
        return closeValidated(lockedConversation);
    }

    private void requireActive(GroupConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || !GroupChatConstant.STATUS_ACTIVE.equals(
                conversation.getStatus())) {
            throw new UserRequestException("群聊会话已结束");
        }
    }

    private GroupConversation closeValidated(
            GroupConversation lockedConversation) {
        if (GroupChatConstant.MODE_CHAT.equals(
                lockedConversation.getMode())) {
            GroupConversation result = transactionTemplate.execute(
                    status -> closeWithoutSummary(lockedConversation));
            if (result == null) {
                throw new IllegalStateException(
                        "结束群聊事务未返回结果");
            }
            return result;
        }
        List<GroupContextSummary> sceneSummaries =
                completedSceneSummaries(lockedConversation.getId());
        String summary = generateSummary(
                lockedConversation, sceneSummaries);
        GroupConversation result = transactionTemplate.execute(status ->
                saveFinalSummary(
                        lockedConversation, sceneSummaries, summary));
        if (result == null) {
            throw new IllegalStateException("结束群聊事务未返回结果");
        }
        saveWorldEvent(result, summary);
        return result;
    }

    private GroupConversation closeWithoutSummary(
            GroupConversation conversation) {
        LocalDateTime now = LocalDateTime.now();
        replyPlanService.clearConversationPlans(conversation);
        conversation.setSummary(null)
                .setStatus(GroupChatConstant.STATUS_CLOSED)
                .setClosedAt(now)
                .setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return conversation;
    }

    private GroupConversation saveFinalSummary(GroupConversation conversation, List<GroupContextSummary> sceneSummaries,
                                               String summary) {
        summaryMapper.delete(new LambdaQueryWrapper<GroupContextSummary>()
                .eq(GroupContextSummary::getConversationId, conversation.getId()));
        if (!sceneSummaries.isEmpty()) {
            summaryMapper.insert(new GroupContextSummary()
                    .setConversationId(conversation.getId())
                    .setStartSequence(sceneSummaries.getFirst()
                            .getStartSequence())
                    .setEndSequence(sceneSummaries.getLast()
                            .getEndSequence())
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

    private List<GroupContextSummary> completedSceneSummaries(
            Long conversationId) {
        List<GroupContextSummary> summaries = summaryMapper.selectList(
                new LambdaQueryWrapper<GroupContextSummary>()
                        .eq(GroupContextSummary::getConversationId,
                                conversationId)
                        .isNotNull(GroupContextSummary::getScenePlanId)
                        .orderByAsc(
                                GroupContextSummary::getStartSequence)
                        .orderByDesc(
                                GroupContextSummary::getEndSequence));
        return summaryIntervalSelector.select(summaries);
    }

    private String generateSummary(GroupConversation conversation, List<GroupContextSummary> sceneSummaries) {
        if (sceneSummaries.isEmpty()) {
            return conversation.getTitle();
        }
        StringBuilder prompt = new StringBuilder();
        appendLine(prompt, "标题", conversation.getTitle());
        prompt.append("已完成场景摘要：\n");
        for (GroupContextSummary sceneSummary : sceneSummaries) {
            prompt.append("[场景 ")
                    .append(sceneSummary.getStartSequence())
                    .append('-')
                    .append(sceneSummary.getEndSequence())
                    .append("] ")
                    .append(sceneSummary.getSummary())
                    .append('\n');
        }
        String summary = summaryClient.prompt(new Prompt(List.of(
                        new SystemMessage("""
                                你负责将一场COC跑团的场景摘要合并为最终概要。
                                概要应包含起因、重要行动、结果、状态变化和仍未解决的问题，适合后续长期检索。
                                只能使用输入的场景摘要，不得把角色猜测写成事实，不得添加新事件。
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
