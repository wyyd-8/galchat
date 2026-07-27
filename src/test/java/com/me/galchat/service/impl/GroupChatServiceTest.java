package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupAgentPolicy;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupContextPolicy;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupModelInvocation;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import com.me.galchat.groupchat.tool.GroupToolContextFactory;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.model.DeepSeekChatModel;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
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
        GroupRuntimeRegistry runtimeRegistry = mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupTurnPolicy turnPolicy = mock(GroupTurnPolicy.class);
        GroupContextPolicy contextPolicy = mock(GroupContextPolicy.class);
        GroupAgentPolicy agentPolicy = mock(GroupAgentPolicy.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper = mock(GroupChatReplyStepMapper.class);
        GroupTurnRecoveryService recoveryService = mock(GroupTurnRecoveryService.class);
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GroupChatService service = new GroupChatService(conversationService, lockService, replyPlanService,
                runtimeRegistry, messageMapper, turnMapper, stepMapper, recoveryService, new GroupToolContextFactory(),
                userWorldPrefixService, transactionTemplate);

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
                .setId(7L).setUserWorldId(1L).setWorldId(2L).setMode(GroupChatConstant.MODE_CHAT)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(userWorldPrefixService.getById(1L))
                .thenReturn(new UserWorldPrefix().setFavorSystemStatus("NORMAL"));
        GroupReplyPlanItem planItem = new GroupReplyPlanItem()
                .setId(20L)
                .setGroupKey("scene:100")
                .setGroupName("地下室")
                .setGroupOrder(1)
                .setItemOrder(2)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L);
        GroupReplyPlanSelection selection = new GroupReplyPlanSelection(
                GroupChatConstant.PLAN_SOURCE_SCENE,
                100L,
                "scene:100",
                "地下室",
                1,
                List.of(planItem));
        when(replyPlanService.currentGroupForExecution(conversation)).thenReturn(selection);
        GroupActionSpec action = new GroupActionSpec(
                GroupChatConstant.ACTION_CHAT_REPLY,
                GroupChatConstant.ACTOR_CHARACTER,
                9L,
                "scene:100",
                "地下室",
                1,
                2);
        when(runtimeRegistry.require(GroupChatConstant.MODE_CHAT)).thenReturn(runtime);
        when(runtime.turnPolicy()).thenReturn(turnPolicy);
        when(runtime.contextPolicy()).thenReturn(contextPolicy);
        when(runtime.agentPolicy()).thenReturn(agentPolicy);
        when(turnPolicy.plan(conversation, selection))
                .thenReturn(List.of(action));
        GroupContextMaterial context = new GroupContextMaterial(List.of(new UserMessage("用户消息")));
        when(contextPolicy.load(conversation, action)).thenReturn(context);
        when(agentPolicy.prepare(conversation, action, context)).thenReturn(new GroupModelInvocation(
                chatClient,
                new Prompt(List.of(new SystemMessage("群聊规则"), new UserMessage("用户消息"))),
                List.of()));
        when(agentPolicy.actorName(
                1L, new GroupActorRef(GroupChatConstant.ACTOR_CHARACTER, 9L))).thenReturn("Alice");
        when(lockService.tryLock(7L)).thenReturn(new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        AtomicLong sequence = new AtomicLong();
        when(conversationService.nextSequence(7L)).thenAnswer(invocation -> sequence.incrementAndGet());

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
        AtomicReference<Prompt> modelPrompt = new AtomicReference<>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            modelPrompt.set(invocation.getArgument(0));
            return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(output))));
        });

        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setContent("你好");

        List<GroupChatEvent> events = service.chat(7L, request).collectList().block();
        List<GroupChatEvent> secondEvents = service.chat(7L, request).collectList().block();

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
        assertThat(events).filteredOn(event -> event.getReplyStepId() != null)
                .allSatisfy(event -> {
                    assertThat(event.getActionType()).isEqualTo(GroupChatConstant.ACTION_CHAT_REPLY);
                    assertThat(event.getGroupKey()).isEqualTo("scene:100");
                    assertThat(event.getGroupOrder()).isEqualTo(1);
                    assertThat(event.getItemOrder()).isEqualTo(2);
                });
        assertThat(secondEvents).extracting(GroupChatEvent::getEventType)
                .containsExactlyElementsOf(events.stream().map(GroupChatEvent::getEventType).toList());
        verify(contextPolicy, times(2)).onTurnStarted(eq(conversation),
                org.mockito.ArgumentMatchers.argThat(message -> "你好".equals(message.getContent())));
        verify(contextPolicy, times(2)).load(conversation, action);
        verify(agentPolicy, times(2)).prepare(conversation, action, context);
        verify(replyPlanService, times(2)).currentGroupForExecution(conversation);
        verifyNoMoreInteractions(replyPlanService);
        var turnCaptor = org.mockito.ArgumentCaptor.forClass(GroupChatTurn.class);
        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        assertThat(turnCaptor.getAllValues()).allSatisfy(turn -> assertThat(turn)
                .extracting(GroupChatTurn::getPlanSource, GroupChatTurn::getPlanContextId)
                .containsExactly(GroupChatConstant.PLAN_SOURCE_SCENE, 100L));
        var stepCaptor = org.mockito.ArgumentCaptor.forClass(GroupChatReplyStep.class);
        verify(stepMapper, times(2)).insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues()).allSatisfy(step -> assertThat(step)
                .extracting(
                        GroupChatReplyStep::getGroupKey,
                        GroupChatReplyStep::getGroupName,
                        GroupChatReplyStep::getGroupOrder,
                        GroupChatReplyStep::getItemOrder,
                        GroupChatReplyStep::getSpeakerId)
                .containsExactly("scene:100", "地下室", 1, 2, 9L));
        assertThat(modelPrompt.get().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(((ToolCallingChatOptions) modelPrompt.get().getOptions()).getToolContext())
                .containsEntry(ChatToolContextConstant.WORLD_ID_KEY, 2L)
                .containsEntry(ChatToolContextConstant.USER_WORLD_ID_KEY, 1L)
                .containsEntry(ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L)
                .containsEntry(ChatToolContextConstant.CHARACTER_ID_KEY, 9L)
                .containsEntry(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 107L)
                .containsEntry(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY, "NORMAL");
    }

    @Test
    void contextPreparationFailureFailsCurrentStepAndCancelsLaterStepsWithoutStreamingMessage() {
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupReplyPlanService replyPlanService = mock(GroupReplyPlanService.class);
        GroupRuntimeRegistry runtimeRegistry = mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupTurnPolicy turnPolicy = mock(GroupTurnPolicy.class);
        GroupContextPolicy contextPolicy = mock(GroupContextPolicy.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper = mock(GroupChatReplyStepMapper.class);
        GroupTurnRecoveryService recoveryService = mock(GroupTurnRecoveryService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GroupChatService service = new GroupChatService(
                conversationService, lockService, replyPlanService, runtimeRegistry,
                messageMapper, turnMapper, stepMapper, recoveryService, new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class), transactionTemplate);

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
                .setId(7L).setUserWorldId(1L).setWorldId(2L)
                .setMode(GroupChatConstant.MODE_CHAT)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(lockService.tryLock(7L))
                .thenReturn(new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(runtimeRegistry.require(GroupChatConstant.MODE_CHAT)).thenReturn(runtime);
        when(runtime.turnPolicy()).thenReturn(turnPolicy);
        when(runtime.contextPolicy()).thenReturn(contextPolicy);
        when(runtime.agentPolicy()).thenReturn(mock(GroupAgentPolicy.class));
        GroupReplyPlanItem first = planItem(20L, 9L, 1);
        GroupReplyPlanItem second = planItem(21L, 8L, 2);
        GroupReplyPlanSelection selection = new GroupReplyPlanSelection(
                GroupChatConstant.PLAN_SOURCE_USER, null,
                "default", "群聊", 1, List.of(first, second));
        when(replyPlanService.currentGroupForExecution(conversation)).thenReturn(selection);
        GroupActionSpec firstAction = action(9L, 1);
        GroupActionSpec secondAction = action(8L, 2);
        when(turnPolicy.plan(conversation, selection)).thenReturn(List.of(firstAction, secondAction));
        when(contextPolicy.load(conversation, firstAction))
                .thenThrow(new IllegalStateException("context failed"));
        when(conversationService.nextSequence(7L)).thenReturn(1L);

        AtomicLong ids = new AtomicLong(200L);
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

        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setContent("开始");

        List<GroupChatEvent> events = service.chat(7L, request).collectList().block();

        assertThat(events).extracting(GroupChatEvent::getEventType).containsExactly(
                GroupChatConstant.EVENT_TURN_ACCEPTED,
                GroupChatConstant.EVENT_REPLY_FAILED);
        verify(stepMapper).updateById(org.mockito.ArgumentMatchers.argThat((GroupChatReplyStep step) ->
                GroupChatConstant.STATUS_FAILED.equals(step.getStatus())));
        verify(messageMapper, never()).insert(org.mockito.ArgumentMatchers.argThat((GroupChatMessage message) ->
                GroupChatConstant.STATUS_STREAMING.equals(message.getStatus())));
        verify(recoveryService).cancelPendingSteps(201L, "context failed");
        verify(contextPolicy, never()).load(conversation, secondAction);
    }

    @Test
    void completedTurnCannotBeRegressedToCancelled() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatService service = new GroupChatService(
                mock(GroupConversationService.class),
                mock(GroupConversationLockService.class),
                mock(GroupReplyPlanService.class),
                mock(GroupRuntimeRegistry.class),
                mock(GroupChatMessageMapper.class),
                turnMapper,
                mock(GroupChatReplyStepMapper.class),
                mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class),
                mock(TransactionTemplate.class));
        GroupChatTurn turn = new GroupChatTurn()
                .setId(7L)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
        when(turnMapper.update(any(GroupChatTurn.class), any())).thenReturn(1);

        service.completeTurn(turn);
        service.cancelTurn(turn);

        assertThat(turn.getStatus()).isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        verify(turnMapper).update(any(GroupChatTurn.class), any());
    }

    @Test
    void failedFinalizationCanBeRetried() {
        GroupChatService.FinalizationGuard guard = new GroupChatService.FinalizationGuard();
        AtomicInteger attempts = new AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.run(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("database failed");
        })).isInstanceOf(IllegalStateException.class);
        guard.run(attempts::incrementAndGet);
        guard.run(attempts::incrementAndGet);

        assertThat(attempts.get()).isEqualTo(2);
    }

    private GroupReplyPlanItem planItem(Long id, Long actorId, int order) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setGroupKey("default")
                .setGroupName("群聊")
                .setGroupOrder(1)
                .setItemOrder(order)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId);
    }

    private GroupActionSpec action(Long actorId, int order) {
        return new GroupActionSpec(
                GroupChatConstant.ACTION_CHAT_REPLY,
                GroupChatConstant.ACTOR_CHARACTER,
                actorId,
                "default",
                "群聊",
                1,
                order);
    }
}
