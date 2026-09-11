package com.me.galchat.service.impl.group;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;

@Service
public class GroupConversationLifecycleService {

    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanService replyPlanService;
    private final GroupContextSummaryMapper summaryMapper;
    private final GroupTurnRecoveryService recoveryService;
    private final TransactionTemplate transactionTemplate;

    public GroupConversationLifecycleService(GroupConversationService conversationService,
                                             GroupConversationLockService lockService,
                                             GroupConversationMapper conversationMapper,
                                             GroupReplyPlanService replyPlanService,
                                             GroupContextSummaryMapper summaryMapper,
                                             GroupTurnRecoveryService recoveryService,
                                             TransactionTemplate transactionTemplate) {
        this.conversationService = conversationService;
        this.lockService = lockService;
        this.conversationMapper = conversationMapper;
        this.replyPlanService = replyPlanService;
        this.summaryMapper = summaryMapper;
        this.recoveryService = recoveryService;
        this.transactionTemplate = transactionTemplate;
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
        recoveryService.assertCanCloseConversation(conversationId);
        return closeValidated(lockedConversation);
    }

    public void closeWithCompletionUnderLock(GroupConversation conversation,
            com.me.galchat.domain.dto.TrpgCompletionModels.Materials materials, String summary) {
        requireActive(conversation);
        recoveryService.assertConversationHasNoNonTerminalTurns(conversation.getId());
        // Preserve source summaries so loading a pre-completion save can restore the original history.
        summaryMapper.insert(new GroupContextSummary().setConversationId(conversation.getId())
                .setStartSequence(materials.sources().isEmpty() ? 1L : materials.sources().getFirst().startSequence())
                .setEndSequence(materials.endSequence()).setSummary(summary).setVersion(1).setCreatedAt(LocalDateTime.now()));
        LocalDateTime now = LocalDateTime.now();
        replyPlanService.clearConversationPlans(conversation);
        conversation.setSummary(summary).setStatus(GroupChatConstant.STATUS_CLOSED).setClosedAt(now).setUpdatedAt(now);
        conversationMapper.updateById(conversation);
    }

    private void requireActive(GroupConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || !GroupChatConstant.STATUS_ACTIVE.equals(
                conversation.getStatus())) {
            throw new UserRequestException("群聊会话已结束");
        }
    }

    private GroupConversation closeValidated(GroupConversation conversation) {
        return transactionTemplate.execute(status -> {
            recoveryService.cancelFailedTurns(conversation.getId());
            conversationService.discardCompletion(conversation.getId());
            return closeWithoutSummary(conversation);
        });
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

}
