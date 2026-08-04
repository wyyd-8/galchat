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
import com.me.galchat.domain.vo.GroupChatMessageVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
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
    void trpgCharacterStepSplitsDecisionAndActionFromOneModelStream() {
        DeepSeekChatModel model = mock(DeepSeekChatModel.class);
        ChatClient chatClient = ChatClient.builder(model).build();
        GroupRuntimeRegistry runtimeRegistry =
                mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupContextPolicy contextPolicy = mock(GroupContextPolicy.class);
        GroupAgentPolicy agentPolicy = mock(GroupAgentPolicy.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupAgentDecisionStore decisionStore =
                mock(GroupAgentDecisionStore.class);
        GroupTurnCheckpointService checkpointService =
                mock(GroupTurnCheckpointService.class);
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatService service = new GroupChatService(
                conversationService,
                mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class),
                runtimeRegistry,
                messageMapper,
                mock(GroupChatTurnMapper.class),
                stepMapper,
                mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class),
                immediateTransactionTemplate(),
                diceMessageCodec(),
                JsonMapper.builder().build(),
                emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                decisionStore,
                mock(TrpgCombatLifecycleService.class),
                checkpointService);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setUserWorldId(5L).setWorldId(2L)
                .setMode(GroupChatConstant.MODE_TRPG);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(30L).setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setPlanContextId(21L);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(31L).setTurnId(30L)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                .setSpeakerId(9L)
                .setGroupKey("scene:21").setGroupName("书房")
                .setGroupOrder(1).setItemOrder(1)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        GroupActionSpec action = new GroupActionSpec(
                step.getActionType(), step.getSpeakerType(),
                step.getSpeakerId(), step.getGroupKey(),
                step.getGroupName(), step.getGroupOrder(),
                step.getItemOrder());
        GroupContextMaterial context =
                new GroupContextMaterial(List.of());
        when(runtimeRegistry.require(GroupChatConstant.MODE_TRPG))
                .thenReturn(runtime);
        when(runtime.contextPolicy()).thenReturn(contextPolicy);
        when(runtime.agentPolicy()).thenReturn(agentPolicy);
        when(contextPolicy.load(conversation, action))
                .thenReturn(context);
        when(agentPolicy.prepare(conversation, action, context))
                .thenReturn(new GroupModelInvocation(
                        chatClient,
                        new Prompt(List.of(new UserMessage("行动"))),
                        List.of()));
        when(agentPolicy.actorName(
                5L,
                new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L)))
                .thenReturn("爱丽丝");
        when(messageMapper.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0)
                            .setId(40L);
                    return 1;
                });
        when(conversationService.nextSequence(7L)).thenReturn(1L);
        DeepSeekAssistantMessage output =
                new DeepSeekAssistantMessage.Builder()
                        .reasoningContent("原始推理")
                        .content("<decision>先确认窗边脚印的方向。</decision>"
                                + "<action>我蹲到窗边检查脚印。</action>")
                        .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.just(new ChatResponse(
                        List.of(new Generation(output)))));

        List<GroupChatEvent> events = service.streamPersistedStep(
                conversation, turn, step).collectList().block();

        assertThat(events).extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_REPLY_STARTED,
                        GroupChatConstant.EVENT_REASONING_DELTA,
                        GroupChatConstant.EVENT_DECISION_DELTA,
                        GroupChatConstant.EVENT_DECISION_COMPLETED,
                        GroupChatConstant.EVENT_MESSAGE_DELTA,
                        GroupChatConstant.EVENT_MESSAGE_COMPLETED);
        verify(decisionStore).save(
                31L, "先确认窗边脚印的方向。");
        verify(messageMapper).updateById(
                org.mockito.ArgumentMatchers.argThat(
                        (GroupChatMessage message) ->
                                "我蹲到窗边检查脚印。".equals(
                                        message.getContent())
                                        && GroupChatConstant.STATUS_COMPLETED
                                        .equals(message.getStatus())));
        verify(model).stream(any(Prompt.class));
        verify(checkpointService).initializeStep(turn, step);
        verify(checkpointService).recordBoundary(
                turn, step,
                GroupTurnCheckpointService.COMPLETED);
    }

    @Test
    void legacyChatEndpointRejectsTrpgConversations() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        GroupRuntimeRegistry runtimeRegistry =
                mock(GroupRuntimeRegistry.class);
        GroupChatService service = new GroupChatService(
                conversationService, lockService,
                mock(GroupTurnPlanResolver.class), runtimeRegistry,
                mock(GroupChatMessageMapper.class),
                mock(GroupChatTurnMapper.class),
                mock(GroupChatReplyStepMapper.class), recoveryService,
                new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class),
                mock(TransactionTemplate.class), diceMessageCodec(),
                JsonMapper.builder().build(), emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setContent("绕过行动轮");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.chat(7L, request).collectList().block())
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("行动轮接口");
        verify(recoveryService, never()).recoverInterrupted(7L);
        verifyNoMoreInteractions(runtimeRegistry);
    }

    @Test
    void streamsThinkingWithoutPersistingIt() {
        DeepSeekChatModel model = mock(DeepSeekChatModel.class);
        ChatClient chatClient = ChatClient.builder(model).build();
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupTurnPlanResolver turnPlanResolver = mock(GroupTurnPlanResolver.class);
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
        GroupChatService service = new GroupChatService(conversationService, lockService, turnPlanResolver,
                runtimeRegistry, messageMapper, turnMapper, stepMapper, recoveryService, new GroupToolContextFactory(),
                userWorldPrefixService, transactionTemplate, diceMessageCodec(),
                JsonMapper.builder().build(), emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));

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
        when(turnPlanResolver.resolve(conversation, runtime))
                .thenReturn(new GroupTurnPlanResolver.ResolvedTurnPlan(
                        GroupChatConstant.PLAN_SOURCE_SCENE, 100L, List.of(action)));
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
        verify(turnPlanResolver, times(2)).resolve(conversation, runtime);
        verify(turnPlanResolver, times(2)).onTurnCompleted(
                conversation, GroupChatConstant.PLAN_SOURCE_SCENE);
        verifyNoMoreInteractions(turnPlanResolver);
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
        var messageCaptor =
                org.mockito.ArgumentCaptor.forClass(GroupChatMessage.class);
        verify(messageMapper, times(4)).insert(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .allSatisfy(message ->
                        assertThat(message.getSceneId()).isEqualTo(100L));
    }

    @Test
    void contextPreparationFailureFailsCurrentStepAndCancelsLaterStepsWithoutStreamingMessage() {
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupTurnPlanResolver turnPlanResolver = mock(GroupTurnPlanResolver.class);
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
                conversationService, lockService, turnPlanResolver, runtimeRegistry,
                messageMapper, turnMapper, stepMapper, recoveryService, new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class), transactionTemplate,
                diceMessageCodec(), JsonMapper.builder().build(),
                emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));

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
        GroupActionSpec firstAction = action(9L, 1);
        GroupActionSpec secondAction = action(8L, 2);
        when(turnPlanResolver.resolve(conversation, runtime))
                .thenReturn(new GroupTurnPlanResolver.ResolvedTurnPlan(
                        GroupChatConstant.PLAN_SOURCE_USER, null,
                        List.of(firstAction, secondAction)));
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
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class),
                mock(GroupChatMessageMapper.class),
                turnMapper,
                mock(GroupChatReplyStepMapper.class),
                mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class),
                mock(TransactionTemplate.class),
                diceMessageCodec(),
                JsonMapper.builder().build(),
                emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));
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

    @Test
    void persistedCancelledStepIsSkippedBeforeModelInvocation() {
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatService service = new GroupChatService(
                mock(GroupConversationService.class),
                mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupChatTurnMapper.class),
                stepMapper,
                mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class),
                mock(TransactionTemplate.class),
                diceMessageCodec(),
                JsonMapper.builder().build(),
                emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));
        GroupChatReplyStep scheduled =
                new GroupChatReplyStep().setId(41L)
                        .setStatus(GroupChatConstant.STATUS_PENDING);
        when(stepMapper.selectById(41L)).thenReturn(
                new GroupChatReplyStep().setId(41L)
                        .setStatus(GroupChatConstant.STATUS_CANCELLED));

        assertThat(service.shouldSkipStep(scheduled)).isTrue();
    }

    @Test
    void directDiceToolResponseEmitsDiceEventWithoutPersistingJsonAsDialogue() {
        DeepSeekChatModel model = mock(DeepSeekChatModel.class);
        ChatClient chatClient = ChatClient.builder(model).build();
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupTurnPlanResolver turnPlanResolver = mock(GroupTurnPlanResolver.class);
        GroupRuntimeRegistry runtimeRegistry = mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupTurnPolicy turnPolicy = mock(GroupTurnPolicy.class);
        GroupContextPolicy contextPolicy = mock(GroupContextPolicy.class);
        GroupAgentPolicy agentPolicy = mock(GroupAgentPolicy.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper = mock(GroupChatReplyStepMapper.class);
        TransactionTemplate transactionTemplate = immediateTransactionTemplate();
        GroupChatService service = new GroupChatService(
                conversationService, lockService, turnPlanResolver, runtimeRegistry,
                messageMapper, turnMapper, stepMapper, mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(), mock(IUserWorldPrefixService.class),
                transactionTemplate, diceMessageCodec(), JsonMapper.builder().build(),
                emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));

        GroupConversation conversation = new GroupConversation()
                .setId(7L).setUserWorldId(1L).setWorldId(2L)
                .setMode(GroupChatConstant.MODE_CHAT)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupReplyPlanSelection selection = new GroupReplyPlanSelection(
                GroupChatConstant.PLAN_SOURCE_USER, null,
                "default", "群聊", 1, List.of());
        GroupActionSpec action = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_KP,
                null,
                "default",
                "群聊",
                1,
                1);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(lockService.tryLock(7L))
                .thenReturn(new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(runtimeRegistry.require(GroupChatConstant.MODE_CHAT)).thenReturn(runtime);
        when(runtime.turnPolicy()).thenReturn(turnPolicy);
        when(runtime.contextPolicy()).thenReturn(contextPolicy);
        when(runtime.agentPolicy()).thenReturn(agentPolicy);
        when(turnPlanResolver.resolve(conversation, runtime))
                .thenReturn(new GroupTurnPlanResolver.ResolvedTurnPlan(
                        GroupChatConstant.PLAN_SOURCE_SCENE, null, List.of(action)));
        GroupContextMaterial context = new GroupContextMaterial(List.of(new UserMessage("用户消息")));
        when(contextPolicy.load(conversation, action)).thenReturn(context);
        when(agentPolicy.prepare(conversation, action, context)).thenReturn(new GroupModelInvocation(
                chatClient, new Prompt(List.of(new UserMessage("用户消息"))), List.of()));
        when(agentPolicy.actorName(1L, new GroupActorRef(GroupChatConstant.ACTOR_KP, null)))
                .thenReturn("KP");
        AtomicLong ids = new AtomicLong(100L);
        doAnswer(invocation -> {
            invocation.<GroupChatTurn>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(turnMapper).insert(any(GroupChatTurn.class));
        doAnswer(invocation -> {
            invocation.<GroupChatMessage>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(messageMapper).insert(any(GroupChatMessage.class));
        doAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(stepMapper).insert(any(GroupChatReplyStep.class));
        when(conversationService.nextSequence(7L))
                .thenReturn(1L, 2L);
        String directJson = """
                {"summary":{"id":501,"conversationId":7,"reason":"侦查",
                  "roundCount":3,"status":"COMPLETED"},
                 "results":[{"roundNo":3},{"roundNo":2},{"roundNo":3}],
                 "semanticResult":"陈默成功"}
                """;
        Generation direct = new Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(directJson),
                ChatGenerationMetadata.builder()
                        .finishReason(ToolExecutionResult.FINISH_REASON)
                        .metadata(ToolExecutionResult.METADATA_TOOL_NAME, "requestCheck")
                        .metadata(ToolExecutionResult.METADATA_TOOL_ID, "call-1")
                        .build());
        when(model.stream(any(Prompt.class)))
                .thenReturn(reactor.core.publisher.Flux.just(new ChatResponse(List.of(direct))));

        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setContent("检查书房");
        List<GroupChatEvent> events = service.chat(7L, request).collectList().block();

        assertThat(events).extracting(GroupChatEvent::getEventType)
                .contains(GroupChatConstant.EVENT_DICE_ROLL_CREATED)
                .doesNotContain(GroupChatConstant.EVENT_MESSAGE_DELTA);
        assertThat(events).filteredOn(event ->
                        GroupChatConstant.EVENT_DICE_ROLL_CREATED.equals(event.getEventType()))
                .singleElement()
                .extracting(event -> event.getDiceRoll().summary().getId())
                .isEqualTo(501L);
        verify(messageMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (GroupChatMessage message) ->
                        GroupChatConstant.MESSAGE_DICE_ROLL.equals(message.getMessageKind())
                                && "{\"summaryId\":501,\"roundNos\":[2,3]}"
                                        .equals(message.getContent())
                                && GroupChatConstant.STATUS_COMPLETED.equals(message.getStatus())));
    }

    @Test
    void invalidAgentSelectionTextIsBufferedAndOnlyCanonicalChoiceIsStored() {
        DeepSeekChatModel model = mock(DeepSeekChatModel.class);
        ChatClient chatClient = ChatClient.builder(model).build();
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupRuntimeRegistry runtimeRegistry =
                mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupContextPolicy contextPolicy = mock(GroupContextPolicy.class);
        GroupAgentPolicy agentPolicy = mock(GroupAgentPolicy.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgSceneSelectionService selectionService =
                mock(TrpgSceneSelectionService.class);
        GroupChatService service = new GroupChatService(
                conversationService,
                mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class),
                runtimeRegistry,
                messageMapper,
                mock(GroupChatTurnMapper.class),
                stepMapper,
                mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class),
                immediateTransactionTemplate(),
                diceMessageCodec(),
                JsonMapper.builder().build(),
                emptyMaterialFeed(),
                selectionService,
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setUserWorldId(5L).setWorldId(2L)
                .setMode(GroupChatConstant.MODE_TRPG);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(30L)
                .setConversationId(7L)
                .setPlanSource(
                        GroupChatConstant.TURN_SOURCE_SCENE_SELECTION);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(31L).setTurnId(30L)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_SCENE_SELECTION)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                .setSpeakerId(9L)
                .setGroupKey("scene-selection")
                .setGroupName("选景")
                .setGroupOrder(1).setItemOrder(2)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        GroupActionSpec action = new GroupActionSpec(
                step.getActionType(), step.getSpeakerType(),
                step.getSpeakerId(), step.getGroupKey(),
                step.getGroupName(), step.getGroupOrder(),
                step.getItemOrder());
        when(runtimeRegistry.require(GroupChatConstant.MODE_TRPG))
                .thenReturn(runtime);
        when(runtime.contextPolicy()).thenReturn(contextPolicy);
        when(runtime.agentPolicy()).thenReturn(agentPolicy);
        GroupContextMaterial context =
                new GroupContextMaterial(List.of());
        when(contextPolicy.load(conversation, action))
                .thenReturn(context);
        when(agentPolicy.prepare(conversation, action, context))
                .thenReturn(new GroupModelInvocation(
                        chatClient,
                        new Prompt(List.of(new UserMessage("选景"))),
                        List.of()));
        when(agentPolicy.actorName(
                5L,
                new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L)))
                .thenReturn("爱丽丝");
        when(model.stream(any(Prompt.class))).thenReturn(
                reactor.core.publisher.Flux.just(
                        new ChatResponse(List.of(new Generation(
                                new org.springframework.ai.chat.messages
                                        .AssistantMessage(
                                        "我考虑后选择二号地点"))))));
        when(selectionService.selectOption(
                7L, 30L,
                new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L),
                null)).thenReturn(
                new TrpgSceneSelectionService.SceneChoiceResult(
                        "2", "爱丽丝", "玛格丽特",
                        "餐厅", true));
        when(conversationService.nextSequence(7L))
                .thenReturn(8L);
        when(messageMapper.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0)
                            .setId(40L);
                    return 1;
                });

        List<GroupChatEvent> events = service.streamPersistedStep(
                conversation, turn, step).collectList().block();

        assertThat(events)
                .extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_SCENE_CHOICE_CREATED,
                        GroupChatConstant.EVENT_MESSAGE_COMPLETED)
                .doesNotContain(
                        GroupChatConstant.EVENT_MESSAGE_DELTA,
                        GroupChatConstant.EVENT_REASONING_DELTA);
        verify(messageMapper).insert(
                org.mockito.ArgumentMatchers.argThat(
                        (GroupChatMessage message) ->
                                "爱丽丝:玛格丽特:餐厅".equals(
                                        message.getContent())
                                        && !message.getContent()
                                                .contains("考虑")));
    }

    @Test
    void historyReturnsStoredDiceReferenceWithoutToolCallLookup() {
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupRuntimeRegistry runtimeRegistry = mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupAgentPolicy agentPolicy = mock(GroupAgentPolicy.class);
        GroupChatService service = new GroupChatService(
                conversationService, mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class), runtimeRegistry,
                messageMapper, mock(GroupChatTurnMapper.class),
                mock(GroupChatReplyStepMapper.class), mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(), mock(IUserWorldPrefixService.class),
                mock(TransactionTemplate.class), diceMessageCodec(),
                JsonMapper.builder().build(), emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                mock(GroupAgentDecisionStore.class),
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        when(conversationService.requireAuthorized(7L)).thenReturn(conversation);
        when(runtimeRegistry.require(GroupChatConstant.MODE_TRPG)).thenReturn(runtime);
        when(runtime.agentPolicy()).thenReturn(agentPolicy);
        when(agentPolicy.actorName(
                null, new GroupActorRef(GroupChatConstant.ACTOR_KP, null))).thenReturn("KP");
        when(messageMapper.selectList(any())).thenReturn(List.of(new GroupChatMessage()
                .setId(91L)
                .setConversationId(7L)
                .setTurnId(31L)
                .setReplyStepId(41L)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                .setContent("{\"summaryId\":501,\"roundNos\":[2]}")
                .setSequenceNo(4L)
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(LocalDateTime.of(2026, 7, 27, 12, 0))));
        GroupChatMessageVO message = service.listHistory(7L, null, 50).getFirst();

        assertThat(message.getMessageKind()).isEqualTo(GroupChatConstant.MESSAGE_DICE_ROLL);
        assertThat(message.getContent())
                .isEqualTo("{\"summaryId\":501,\"roundNos\":[2]}");
    }

    @Test
    void historyAttachesStoredDecisionToCharacterAction() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupAgentDecisionStore decisionStore =
                mock(GroupAgentDecisionStore.class);
        GroupRuntimeRegistry runtimeRegistry =
                mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupAgentPolicy agentPolicy = mock(GroupAgentPolicy.class);
        GroupChatService service = new GroupChatService(
                conversationService,
                mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class),
                runtimeRegistry,
                messageMapper,
                mock(GroupChatTurnMapper.class),
                mock(GroupChatReplyStepMapper.class),
                mock(GroupTurnRecoveryService.class),
                new GroupToolContextFactory(),
                mock(IUserWorldPrefixService.class),
                mock(TransactionTemplate.class),
                diceMessageCodec(),
                JsonMapper.builder().build(),
                emptyMaterialFeed(),
                mock(TrpgSceneSelectionService.class),
                decisionStore,
                mock(TrpgCombatLifecycleService.class),
                mock(GroupTurnCheckpointService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(runtimeRegistry.require(GroupChatConstant.MODE_TRPG))
                .thenReturn(runtime);
        when(runtime.agentPolicy()).thenReturn(agentPolicy);
        when(agentPolicy.actorName(
                null,
                new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L)))
                .thenReturn("爱丽丝");
        when(messageMapper.selectList(any())).thenReturn(
                List.of(new GroupChatMessage()
                        .setId(91L)
                        .setConversationId(7L)
                        .setTurnId(31L)
                        .setReplyStepId(41L)
                        .setSpeakerType(
                                GroupChatConstant.ACTOR_CHARACTER)
                        .setSpeakerId(9L)
                        .setMessageKind(
                                GroupChatConstant.MESSAGE_DIALOGUE)
                        .setContent("我检查窗框。")
                        .setSequenceNo(4L)
                        .setStatus(GroupChatConstant.STATUS_COMPLETED)
                        .setCreatedAt(LocalDateTime.of(
                                2026, 7, 30, 12, 0))));
        when(decisionStore.contentByReplyStepIds(List.of(41L)))
                .thenReturn(java.util.Map.of(
                        41L, "窗边泥点可能来自外面。"));

        GroupChatMessageVO message =
                service.listHistory(7L, null, 50).getFirst();

        assertThat(message.getDecisionContent())
                .isEqualTo("窗边泥点可能来自外面。");
    }

    private TransactionTemplate immediateTransactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(template).executeWithoutResult(any());
        return template;
    }

    private GroupMaterialMessageFeed emptyMaterialFeed() {
        GroupMaterialMessageFeed feed = mock(GroupMaterialMessageFeed.class);
        when(feed.listNew(any(), any())).thenReturn(List.of());
        return feed;
    }

    private DiceRollMessageCodec diceMessageCodec() {
        return new DiceRollMessageCodec(JsonMapper.builder().build());
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
