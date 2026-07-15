package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.groupchat.context.GroupContextStrategyRouter;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.model.DeepSeekChatModel;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupChatServiceTest {

    @Test
    void streamsThinkingWithoutPersistingIt() {
        DeepSeekChatModel model = mock(DeepSeekChatModel.class);
        ChatClient chatClient = ChatClient.builder(model).build();
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupReplyPlanService replyPlanService = mock(GroupReplyPlanService.class);
        GroupContextAssembler assembler = mock(GroupContextAssembler.class);
        GroupContextStrategyRouter contextStrategyRouter = mock(GroupContextStrategyRouter.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper = mock(GroupChatReplyStepMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GroupChatService service = new GroupChatService(chatClient, conversationService, lockService, replyPlanService,
                assembler, contextStrategyRouter, messageMapper, turnMapper, stepMapper,
                transactionTemplate);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        GroupConversation conversation = new GroupConversation()
                .setId(7L).setUserWorldId(1L).setWorldId(2L).setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        GroupReplyPlanItem planItem = new GroupReplyPlanItem()
                .setId(20L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(replyPlanService.pendingItemsForExecution(conversation)).thenReturn(List.of(planItem));
        when(replyPlanService.activeSource(conversation)).thenReturn(GroupChatConstant.PLAN_SOURCE_USER);
        when(lockService.tryLock(7L)).thenReturn(new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        AtomicLong sequence = new AtomicLong();
        when(conversationService.nextSequence(7L)).thenAnswer(invocation -> sequence.incrementAndGet());
        when(assembler.characterName(1L, 9L)).thenReturn("Alice");
        when(assembler.assemble(conversation, 9L)).thenReturn(List.of(
                new SystemMessage("群聊规则"), new UserMessage("用户消息")));

        AtomicLong ids = new AtomicLong(100L);
        doAnswer(invocation -> {
            ((GroupChatTurn) invocation.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(turnMapper).insert(any(GroupChatTurn.class));
        doAnswer(invocation -> {
            ((GroupChatMessage) invocation.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(messageMapper).insert(any(GroupChatMessage.class));
        doAnswer(invocation -> {
            ((GroupChatReplyStep) invocation.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(stepMapper).insert(any(GroupChatReplyStep.class));

        DeepSeekAssistantMessage output = new DeepSeekAssistantMessage.Builder()
                .reasoningContent("只供前端展示的思考")
                .content("公开回复")
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(output)))));

        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setContent("你好");

        List<GroupChatEvent> events = service.chat(7L, request).collectList().block();

        assertThat(events).extracting(GroupChatEvent::getEventType).containsExactly(
                GroupChatConstant.EVENT_TURN_ACCEPTED,
                GroupChatConstant.EVENT_REPLY_STARTED,
                GroupChatConstant.EVENT_REASONING_DELTA,
                GroupChatConstant.EVENT_MESSAGE_DELTA,
                GroupChatConstant.EVENT_MESSAGE_COMPLETED,
                GroupChatConstant.EVENT_TURN_COMPLETED);
        assertThat(events).filteredOn(event -> GroupChatConstant.EVENT_REASONING_DELTA.equals(event.getEventType()))
                .extracting(GroupChatEvent::getDelta)
                .containsExactly("只供前端展示的思考");
        verify(assembler).assemble(conversation, 9L);
        verify(replyPlanService).markCompleted(planItem);
    }
}
