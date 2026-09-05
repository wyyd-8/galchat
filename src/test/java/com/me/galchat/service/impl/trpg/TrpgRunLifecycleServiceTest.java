package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.group.GroupConversationLifecycleService;
import com.me.galchat.service.impl.group.GroupTurnRecoveryService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgRunLifecycleServiceTest {

    @Test
    void requestedRunFinishClosesConversationAfterTurnCompletion() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupConversationLifecycleService conversationLifecycle =
                mock(GroupConversationLifecycleService.class);
        TrpgEpilogueService epilogueService =
                mock(TrpgEpilogueService.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        TrpgSceneSummaryService sceneSummaryService =
                mock(TrpgSceneSummaryService.class);
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values =
                mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(31L);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(stepMapper.selectById(8L)).thenReturn(
                new GroupChatReplyStep()
                        .setId(8L)
                        .setTurnId(9L)
                        .setSpeakerType(GroupChatConstant.ACTOR_KP));
        when(turnMapper.selectById(9L)).thenReturn(
                new GroupChatTurn()
                        .setId(9L)
                        .setConversationId(7L));
        when(values.get(any())).thenReturn("1");
        when(planMapper.selectById(31L)).thenReturn(
                new GroupReplyPlan()
                        .setId(31L)
                        .setContextId(21L)
                        .setParentPlanId(20L));
        when(planMapper.selectById(20L)).thenReturn(
                new GroupReplyPlan()
                        .setId(20L)
                        .setContextId(10L));
        TrpgRunLifecycleService service =
                new TrpgRunLifecycleService(
                        conversationService, stepMapper, turnMapper,
                        redis, conversationLifecycle,
                        recoveryService, epilogueService,
                        planMapper, sceneSummaryService);

        service.requestFinish(7L, 8L);
        boolean closed = service.finalizeAfterTurn(conversation, 9L);

        assertThat(closed).isTrue();
        verify(recoveryService).cancelPendingSteps(
                9L, "KP已结束整个跑团");
        var closureOrder = inOrder(
                epilogueService, sceneSummaryService,
                conversationLifecycle);
        closureOrder.verify(epilogueService)
                .generateAndPersist(conversation, 9L);
        closureOrder.verify(sceneSummaryService)
                .summarize(7L, 21L, 31L);
        closureOrder.verify(sceneSummaryService)
                .summarize(7L, 10L, 20L);
        closureOrder.verify(conversationLifecycle)
                .closeAfterTurnUnderLock(conversation, 9L);
        verify(redis).delete(any(String.class));
    }
}
