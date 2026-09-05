package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.group.GroupConversationLifecycleService;
import com.me.galchat.service.impl.group.GroupTurnRecoveryService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgRunLifecycleService {

    private static final Duration REQUEST_TTL =
            Duration.ofDays(7);

    private final GroupConversationService conversationService;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final StringRedisTemplate redisTemplate;
    private final GroupConversationLifecycleService conversationLifecycle;
    private final GroupTurnRecoveryService recoveryService;
    private final TrpgEpilogueService epilogueService;
    private final GroupReplyPlanMapper planMapper;
    private final TrpgSceneSummaryService sceneSummaryService;

    public void requestFinish(
            Long conversationId, Long replyStepId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        if (!GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("只有TRPG群聊可以结束跑团");
        }
        GroupChatReplyStep step =
                stepMapper.selectById(replyStepId);
        GroupChatTurn turn = step == null
                ? null : turnMapper.selectById(step.getTurnId());
        if (step == null || turn == null
                || !Objects.equals(
                        turn.getConversationId(), conversationId)) {
            throw new UserRequestException("当前回复步骤不属于该跑团");
        }
        if (!GroupChatConstant.ACTOR_KP.equals(
                step.getSpeakerType())) {
            throw new UserAuthException("只有KP可以结束整个跑团");
        }
        redisTemplate.opsForValue().set(
                key(conversationId), "1", REQUEST_TTL);
        recoveryService.cancelPendingSteps(
                turn.getId(), "KP已结束整个跑团");
    }

    public boolean finalizeAfterTurn(
            GroupConversation conversation, Long completingTurnId) {
        if (conversation == null
                || conversation.getId() == null
                || !GroupChatConstant.MODE_TRPG.equals(
                        conversation.getMode())
                || !"1".equals(redisTemplate.opsForValue()
                        .get(key(conversation.getId())))) {
            return false;
        }
        epilogueService.generateAndPersist(
                conversation, completingTurnId);
        summarizeActivePlanHierarchy(conversation);
        conversationLifecycle.closeAfterTurnUnderLock(
                conversation, completingTurnId);
        redisTemplate.delete(key(conversation.getId()));
        return true;
    }

    private void summarizeActivePlanHierarchy(
            GroupConversation conversation) {
        Long planId = conversation.getActiveReplyPlanId();
        Set<Long> visited = new HashSet<>();
        while (planId != null && visited.add(planId)) {
            GroupReplyPlan plan = planMapper.selectById(planId);
            if (plan == null) {
                return;
            }
            sceneSummaryService.summarize(
                    conversation.getId(), plan.getContextId(), plan.getId());
            planId = plan.getParentPlanId();
        }
    }

    private String key(Long conversationId) {
        return RedisConstant.TRPG_RUN_FINISH_PREFIX
                + conversationId;
    }
}
