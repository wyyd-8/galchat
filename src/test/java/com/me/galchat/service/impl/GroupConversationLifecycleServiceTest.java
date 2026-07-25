package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.groupchat.context.GroupTopicService;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.WorldEventLogMapper;
import com.me.galchat.model.DeepSeekChatModel;
import com.me.galchat.service.IWorldEventLogService;
import com.me.galchat.vector.WorldEventVectorService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupConversationLifecycleServiceTest {

    @Test
    void endRewritesFullSummaryAndClosesConversation() {
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanService replyPlanService = mock(GroupReplyPlanService.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupContextSummaryMapper summaryMapper = mock(GroupContextSummaryMapper.class);
        IWorldEventLogService eventLogService = mock(IWorldEventLogService.class);
        WorldEventLogMapper eventLogMapper = mock(WorldEventLogMapper.class);
        WorldEventVectorService eventVectorService = mock(WorldEventVectorService.class);
        GroupTopicService topicService = mock(GroupTopicService.class);
        DeepSeekChatModel summaryModel = mock(DeepSeekChatModel.class);
        ChatClient summaryClient = ChatClient.builder(summaryModel).build();
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GroupConversationLifecycleService service = new GroupConversationLifecycleService(conversationService,
                lockService, conversationMapper, replyPlanService, messageMapper, summaryMapper,
                eventLogService, eventLogMapper,
                eventVectorService, topicService, summaryClient, transactionTemplate);

        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(1L)
                .setMode(GroupChatConstant.MODE_CHAT)
                .setTitle("地下医院")
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireAuthorized(7L)).thenReturn(conversation);
        when(conversationService.listMembers(7L)).thenReturn(List.of(
                new GroupChatMember().setActorId(11L), new GroupChatMember().setActorId(12L)));
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(1L, GroupChatConstant.ACTOR_USER, null, "检查病房"),
                message(2L, GroupChatConstant.ACTOR_CHARACTER, 11L, "发现钥匙")));
        when(summaryModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("众人在医院中发现了钥匙。")))));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        var summaryCaptor = org.mockito.ArgumentCaptor.forClass(GroupContextSummary.class);
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(WorldEventLog.class);

        GroupConversation result = service.close(7L);

        assertThat(result.getStatus()).isEqualTo(GroupChatConstant.STATUS_CLOSED);
        assertThat(result.getClosedAt()).isNotNull();
        assertThat(result.getSummary()).isEqualTo("众人在医院中发现了钥匙。");
        verify(summaryMapper).insert(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getStartSequence()).isEqualTo(1L);
        assertThat(summaryCaptor.getValue().getEndSequence()).isEqualTo(2L);
        verify(eventLogService).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getConversationId()).isEqualTo(7L);
        verify(eventVectorService).addWorldEventLog(eventCaptor.getValue());
        verify(replyPlanService).clearConversationPlans(conversation);
        verify(topicService).flushOpenTopics(conversation);
    }

    private GroupChatMessage message(Long sequence, String type, Long id, String content) {
        return new GroupChatMessage()
                .setConversationId(7L)
                .setSequenceNo(sequence)
                .setSpeakerType(type)
                .setSpeakerId(id)
                .setContent(content)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
    }
}
