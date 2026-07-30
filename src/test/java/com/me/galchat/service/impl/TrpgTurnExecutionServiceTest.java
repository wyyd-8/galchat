package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupTurnStartDTO;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupSceneSelectionDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgTurnExecutionServiceTest {

    @Test
    void retryRerunsFailedCharacterStepAndRestoredBlockedTailOnly() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupTurnPlanResolver planResolver =
                mock(GroupTurnPlanResolver.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatService groupChatService =
                mock(GroupChatService.class);
        GroupAgentDecisionStore decisionStore =
                mock(GroupAgentDecisionStore.class);
        GroupChatToolCallMapper toolCallMapper =
                mock(GroupChatToolCallMapper.class);
        TrpgTurnExecutionService service =
                new TrpgTurnExecutionService(
                        conversationService,
                        lockService,
                        planResolver,
                        mock(GroupRuntimeRegistry.class),
                        turnMapper,
                        stepMapper,
                        messageMapper,
                        mock(GroupTurnRecoveryService.class),
                        groupChatService,
                        immediateTransactionTemplate());
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "decisionStore", decisionStore);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "toolCallMapper", toolCallMapper);
        GroupConversation conversation =
                new GroupConversation()
                        .setId(7L)
                        .setMode(GroupChatConstant.MODE_TRPG)
                        .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep completed =
                new GroupChatReplyStep()
                        .setId(102L).setTurnId(101L)
                        .setStepNo(1)
                        .setStatus(GroupChatConstant.STATUS_COMPLETED);
        GroupChatReplyStep failed =
                new GroupChatReplyStep()
                        .setId(103L).setTurnId(101L)
                        .setStepNo(2)
                        .setActionType(
                                GroupChatConstant.ACTION_TRPG_SCENE)
                        .setSpeakerType(
                                GroupChatConstant.ACTOR_CHARACTER)
                        .setSpeakerId(9L)
                        .setOutputMessageId(203L)
                        .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep blocked =
                new GroupChatReplyStep()
                        .setId(104L).setTurnId(101L)
                        .setStepNo(3)
                        .setActionType(
                                GroupChatConstant.ACTION_TRPG_SCENE)
                        .setSpeakerType(GroupChatConstant.ACTOR_KP)
                        .setStatus(GroupChatConstant.STATUS_BLOCKED);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectById(101L)).thenReturn(turn);
        when(stepMapper.selectById(103L)).thenReturn(failed);
        when(stepMapper.selectList(any()))
                .thenReturn(List.of(failed, blocked));
        when(groupChatService.streamPersistedStep(
                conversation, turn, failed))
                .thenReturn(Flux.empty());
        when(groupChatService.streamPersistedStep(
                conversation, turn, blocked))
                .thenReturn(Flux.empty());

        List<GroupChatEvent> events = service.retry(
                7L, 101L, 103L).collectList().block();

        assertThat(events)
                .extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_COMPLETED);
        verify(decisionStore).deleteByReplyStepId(103L);
        verify(messageMapper).deleteById(203L);
        verify(toolCallMapper).delete(any());
        verify(groupChatService).streamPersistedStep(
                conversation, turn, failed);
        verify(groupChatService).streamPersistedStep(
                conversation, turn, blocked);
        verify(groupChatService, never()).streamPersistedStep(
                conversation, turn, completed);
        assertThat(failed.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        assertThat(failed.getOutputMessageId()).isNull();
        assertThat(blocked.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
    }

    @Test
    void startPausesAtUserStepWithoutExecutingLaterActors() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupTurnPlanResolver planResolver =
                mock(GroupTurnPlanResolver.class);
        GroupRuntimeRegistry runtimeRegistry =
                mock(GroupRuntimeRegistry.class);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatService groupChatService = mock(GroupChatService.class);
        TransactionTemplate transactionTemplate =
                immediateTransactionTemplate();
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService,
                lockService,
                planResolver,
                runtimeRegistry,
                turnMapper,
                stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                groupChatService,
                transactionTemplate);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(31L);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(runtimeRegistry.require(GroupChatConstant.MODE_TRPG))
                .thenReturn(runtime);
        GroupActionSpec agent = action(
                GroupChatConstant.ACTOR_CHARACTER, 11L, 1);
        GroupActionSpec user = action(
                GroupChatConstant.ACTOR_USER, 101L, 2);
        GroupActionSpec kp = action(
                GroupChatConstant.ACTOR_KP, null, 3);
        when(planResolver.resolve(conversation, runtime))
                .thenReturn(new GroupTurnPlanResolver.ResolvedTurnPlan(
                        GroupChatConstant.PLAN_SOURCE_SCENE,
                        21L,
                        List.of(agent, user, kp)));

        AtomicLong ids = new AtomicLong(100L);
        when(turnMapper.insert(any(GroupChatTurn.class)))
                .thenAnswer(invocation -> {
            invocation.<GroupChatTurn>getArgument(0)
                    .setId(ids.incrementAndGet());
            return 1;
        });
        when(stepMapper.insert(any(GroupChatReplyStep.class)))
                .thenAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0)
                    .setId(ids.incrementAndGet());
            return 1;
        });
        when(groupChatService.streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                any(GroupChatTurn.class),
                any(GroupChatReplyStep.class)))
                .thenReturn(Flux.empty());

        GroupTurnStartDTO request = new GroupTurnStartDTO();
        request.setClientRequestId("start-1");
        List<GroupChatEvent> events =
                service.start(7L, request).collectList().block();

        assertThat(events)
                .extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_WAITING_INPUT);
        verify(groupChatService).streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                org.mockito.ArgumentMatchers.argThat(turn ->
                        GroupChatConstant.STATUS_WAITING_INPUT.equals(
                                turn.getStatus())),
                org.mockito.ArgumentMatchers.argThat(step ->
                        GroupChatConstant.ACTOR_CHARACTER.equals(
                                step.getSpeakerType())));
        verify(groupChatService, never()).streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                any(GroupChatTurn.class),
                org.mockito.ArgumentMatchers.argThat(step ->
                        GroupChatConstant.ACTOR_KP.equals(
                                step.getSpeakerType())
                                && GroupChatConstant.ACTION_TRPG_SCENE.equals(
                                        step.getActionType())));
        verify(groupChatService).streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                any(GroupChatTurn.class),
                org.mockito.ArgumentMatchers.argThat(step ->
                        GroupChatConstant.ACTION_TRPG_SCENE_INTRO.equals(
                                step.getActionType())));

        var turnCaptor =
                org.mockito.ArgumentCaptor.forClass(GroupChatTurn.class);
        verify(turnMapper).insert(turnCaptor.capture());
        assertThat(turnCaptor.getValue())
                .extracting(
                        GroupChatTurn::getPlanId,
                        GroupChatTurn::getPlanSource,
                        GroupChatTurn::getPlanContextId,
                        GroupChatTurn::getStatus)
                .containsExactly(
                        31L,
                        GroupChatConstant.PLAN_SOURCE_SCENE,
                        21L,
                        GroupChatConstant.STATUS_WAITING_INPUT);
        var stepCaptor = org.mockito.ArgumentCaptor.forClass(
                GroupChatReplyStep.class);
        verify(stepMapper, org.mockito.Mockito.times(4))
                .insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues().get(2))
                .extracting(
                        GroupChatReplyStep::getSpeakerType,
                        GroupChatReplyStep::getSpeakerId,
                        GroupChatReplyStep::getStatus)
                .containsExactly(
                        GroupChatConstant.ACTOR_USER,
                        101L,
                        GroupChatConstant.STATUS_WAITING_INPUT);
    }

    @Test
    void submitUserMessageContinuesTheSameTurnAndRunsLaterSteps() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupTurnPlanResolver planResolver =
                mock(GroupTurnPlanResolver.class);
        GroupRuntimeRegistry runtimeRegistry =
                mock(GroupRuntimeRegistry.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatService groupChatService = mock(GroupChatService.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService,
                lockService,
                planResolver,
                runtimeRegistry,
                turnMapper,
                stepMapper,
                messageMapper,
                mock(GroupTurnRecoveryService.class),
                groupChatService,
                immediateTransactionTemplate());
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(31L);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setPlanId(31L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setPlanContextId(21L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep userStep = new GroupChatReplyStep()
                .setId(102L)
                .setTurnId(101L)
                .setStepNo(2)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(501L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep kpStep = new GroupChatReplyStep()
                .setId(103L)
                .setTurnId(101L)
                .setStepNo(3)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectById(101L)).thenReturn(turn);
        when(stepMapper.selectById(102L)).thenReturn(userStep);
        when(stepMapper.selectList(any())).thenReturn(List.of(kpStep));
        when(conversationService.nextSequence(7L)).thenReturn(9L);
        when(messageMapper.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0).setId(104L);
                    return 1;
                });
        when(groupChatService.streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                org.mockito.ArgumentMatchers.eq(turn),
                org.mockito.ArgumentMatchers.eq(kpStep)))
                .thenReturn(Flux.empty());

        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setContent("我检查餐桌下面。");
        List<GroupChatEvent> events = service.submitMessage(
                7L, 101L, 102L, request).collectList().block();

        assertThat(events)
                .extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_COMPLETED);
        verify(turnMapper, never()).insert(any(GroupChatTurn.class));
        verify(groupChatService).streamPersistedStep(
                conversation, turn, kpStep);
        var messageCaptor =
                org.mockito.ArgumentCaptor.forClass(GroupChatMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue())
                .extracting(
                        GroupChatMessage::getTurnId,
                        GroupChatMessage::getReplyStepId,
                        GroupChatMessage::getSpeakerType,
                        GroupChatMessage::getSpeakerId,
                        GroupChatMessage::getContent,
                        GroupChatMessage::getSequenceNo)
                .containsExactly(
                        101L, 102L,
                        GroupChatConstant.ACTOR_USER, 501L,
                        "我检查餐桌下面。", 9L);
        assertThat(userStep)
                .extracting(
                        GroupChatReplyStep::getStatus,
                        GroupChatReplyStep::getOutputMessageId)
                .containsExactly(
                        GroupChatConstant.STATUS_COMPLETED, 104L);
        assertThat(turn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        verify(planResolver).onTurnCompleted(
                conversation, turn);
        var completionOrder = org.mockito.Mockito.inOrder(
                planResolver, turnMapper);
        completionOrder.verify(planResolver).onTurnCompleted(
                conversation, turn);
        completionOrder.verify(turnMapper).updateById(
                org.mockito.ArgumentMatchers.argThat(
                        (GroupChatTurn updated) ->
                                GroupChatConstant.STATUS_COMPLETED.equals(
                                        updated.getStatus())));
    }

    @Test
    void startReturnsExistingWaitingStepInsteadOfCreatingAnotherTurn() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService,
                lockService,
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class),
                turnMapper,
                stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(GroupChatService.class),
                immediateTransactionTemplate());
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(102L)
                .setTurnId(101L)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(501L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(stepMapper.selectList(any())).thenReturn(List.of(step));

        GroupTurnStartDTO request = new GroupTurnStartDTO();
        request.setClientRequestId("reconnect-1");
        List<GroupChatEvent> events =
                service.start(7L, request).collectList().block();

        assertThat(events)
                .extracting(
                        GroupChatEvent::getEventType,
                        GroupChatEvent::getTurnId,
                        GroupChatEvent::getReplyStepId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.EVENT_TURN_WAITING_INPUT,
                                101L, 102L));
        verify(turnMapper, never()).insert(any(GroupChatTurn.class));
    }

    @Test
    void userSelectionStoresCanonicalChoiceAndContinuesToKp() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupTurnPlanResolver planResolver =
                mock(GroupTurnPlanResolver.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatService groupChatService = mock(GroupChatService.class);
        TrpgSceneSelectionService selectionService =
                mock(TrpgSceneSelectionService.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService, lockService, planResolver,
                mock(GroupRuntimeRegistry.class), turnMapper, stepMapper,
                messageMapper, mock(GroupTurnRecoveryService.class),
                groupChatService, immediateTransactionTemplate());
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "sceneSelectionService", selectionService);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L).setConversationId(7L)
                .setPlanSource(
                        GroupChatConstant.TURN_SOURCE_SCENE_SELECTION)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep user = new GroupChatReplyStep()
                .setId(102L).setTurnId(101L).setStepNo(2)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_SCENE_SELECTION)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(501L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep kp = new GroupChatReplyStep()
                .setId(103L).setTurnId(101L).setStepNo(3)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_SCENE_SELECTION)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectById(101L)).thenReturn(turn);
        when(stepMapper.selectById(102L)).thenReturn(user);
        when(stepMapper.selectList(any())).thenReturn(List.of(kp));
        when(selectionService.selectOption(
                7L, 101L,
                new com.me.galchat.groupchat.runtime.GroupActorRef(
                        GroupChatConstant.ACTOR_USER, 501L),
                "2")).thenReturn(
                new TrpgSceneSelectionService.SceneChoiceResult(
                        "2", "用户", "林登", "餐厅", false));
        when(messageMapper.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0)
                            .setId(104L);
                    return 1;
                });
        when(groupChatService.streamPersistedStep(
                conversation, turn, kp)).thenReturn(Flux.empty());

        GroupSceneSelectionDTO request =
                new GroupSceneSelectionDTO();
        request.setOptionNo("2");
        service.submitSelection(
                7L, 101L, 102L, request).collectList().block();

        verify(selectionService).selectOption(
                7L, 101L,
                new com.me.galchat.groupchat.runtime.GroupActorRef(
                        GroupChatConstant.ACTOR_USER, 501L),
                "2");
        verify(messageMapper).insert(
                org.mockito.ArgumentMatchers.argThat(
                        (GroupChatMessage message) ->
                                "用户:林登:餐厅".equals(
                                        message.getContent())
                                        && message.getReplyStepId()
                                                .equals(102L)));
        verify(groupChatService).streamPersistedStep(
                conversation, turn, kp);
    }

    @Test
    void lastUserEndingExplorationStillRunsTheKpClosingStep() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatService groupChatService = mock(GroupChatService.class);
        TrpgSceneLifecycleService lifecycleService =
                mock(TrpgSceneLifecycleService.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService, lockService,
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class),
                turnMapper, stepMapper, messageMapper,
                mock(GroupTurnRecoveryService.class),
                groupChatService, immediateTransactionTemplate());
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "sceneLifecycleService", lifecycleService);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L).setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setPlanContextId(21L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep user = new GroupChatReplyStep()
                .setId(102L).setTurnId(101L).setStepNo(2)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(501L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep kp = new GroupChatReplyStep()
                .setId(103L).setTurnId(101L).setStepNo(4)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectById(101L)).thenReturn(turn);
        when(stepMapper.selectById(102L)).thenReturn(user);
        when(stepMapper.selectList(any())).thenReturn(List.of(kp));
        when(messageMapper.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0)
                            .setId(104L);
                    return 1;
                });
        when(groupChatService.streamPersistedStep(
                conversation, turn, kp)).thenReturn(Flux.empty());

        GroupTurnStartDTO request = new GroupTurnStartDTO();
        service.endExploration(
                7L, 101L, 102L, request).collectList().block();

        verify(lifecycleService).requestInvestigatorFinish(
                7L, 102L,
                new com.me.galchat.groupchat.runtime.GroupActorRef(
                        GroupChatConstant.ACTOR_USER, 501L));
        verify(groupChatService).streamPersistedStep(
                conversation, turn, kp);
        verify(messageMapper).insert(
                org.mockito.ArgumentMatchers.argThat(
                        (GroupChatMessage message) ->
                                message.getContent().contains("结束当前场景探索")));
    }

    private GroupActionSpec action(
            String actorType, Long actorId, int order) {
        return new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                actorType,
                actorId,
                "scene:21",
                "餐厅",
                1,
                order);
    }

    private TransactionTemplate immediateTransactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(template).executeWithoutResult(any());
        return template;
    }
}
