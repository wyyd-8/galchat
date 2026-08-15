package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupEndExplorationDTO;
import com.me.galchat.domain.dto.GroupTurnContinueDTO;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupSceneSelectionDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.GroupCurrentTurnVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.service.ITrpgSaveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrpgTurnExecutionServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                GroupChatReplyStep.class);
    }

    @Test
    void unconsciousPlayerAttackPausesForRecoveryBeforeRequestingAction()
            throws Exception {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatService groupChatService = mock(GroupChatService.class);
        TrpgUnconsciousRecoveryService unconsciousRecovery =
                mock(TrpgUnconsciousRecoveryService.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService, lockService,
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class), turnMapper, stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class), groupChatService,
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                unconsciousRecovery,
                mock(ITrpgSaveService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L).setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
        GroupChatReplyStep attack = new GroupChatReplyStep()
                .setId(102L).setTurnId(101L).setStepNo(1)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ATTACK)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSubjectCharacterId(71L)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        GroupChatEvent diceEvent = GroupChatEvent.builder()
                .eventType(GroupChatConstant.EVENT_DICE_ROLL_CREATED)
                .conversationId(7L).turnId(101L).replyStepId(102L)
                .build();
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(turnMapper.selectById(101L)).thenReturn(turn);
        when(stepMapper.selectCount(any())).thenReturn(0L);
        when(stepMapper.selectList(any())).thenReturn(List.of(attack));
        when(stepMapper.selectById(102L)).thenReturn(attack);
        when(unconsciousRecovery.handle(conversation, turn, attack))
                .thenReturn(new TrpgUnconsciousRecoveryService.Execution(
                        TrpgUnconsciousRecoveryService.Outcome.PAUSED,
                        List.of(diceEvent)));

        List<GroupChatEvent> events = service.continueTurn(
                7L, new GroupTurnContinueDTO()).collectList().block();

        assertThat(events).extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_DICE_ROLL_CREATED,
                        GroupChatConstant.EVENT_TURN_PAUSED);
        verify(unconsciousRecovery).handle(conversation, turn, attack);
        verifyNoInteractions(groupChatService);
        assertThat(attack.getStatus())
                .isNotEqualTo(GroupChatConstant.STATUS_WAITING_INPUT);
    }

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
        GroupTurnCheckpointService checkpointService =
                mock(GroupTurnCheckpointService.class);
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
                        immediateTransactionTemplate(),
                        mock(TrpgSceneSelectionService.class),
                        mock(TrpgSceneLifecycleService.class),
                        mock(TrpgSceneSelectionStore.class),
                        mock(TrpgParticipantService.class),
                        decisionStore,
                        mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                        mock(com.me.galchat.groupchat.dice
                                .DiceRollMessageCodec.class),
                        mock(TrpgCombatLifecycleService.class),
                        mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                        checkpointService,
                        mock(TrpgUnconsciousRecoveryService.class),
                        mock(ITrpgSaveService.class));
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
                        .setErrorMessage("前序步骤失败")
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
        when(checkpointService.restore(turn, failed))
                .thenAnswer(invocation -> {
                    failed.setOutputMessageId(null)
                            .setStatus(GroupChatConstant.STATUS_PENDING)
                            .setErrorMessage(null);
                    turn.setStatus(GroupChatConstant.STATUS_RUNNING);
                    return true;
                });
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
        verifyNoInteractions(messageMapper);
        verify(checkpointService).restore(turn, failed);
        verify(checkpointService).clear(7L);
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
        assertThat(blocked.getErrorMessage()).isNull();
        verify(stepMapper, org.mockito.Mockito.atLeastOnce()).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryWithoutCheckpointRerunsTheWholeTurn() {
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
        GroupChatService groupChatService =
                mock(GroupChatService.class);
        GroupAgentDecisionStore decisionStore =
                mock(GroupAgentDecisionStore.class);
        TrpgCombatLifecycleService combatLifecycleService =
                mock(TrpgCombatLifecycleService.class);
        GroupTurnCheckpointService checkpointService =
                mock(GroupTurnCheckpointService.class);
        TrpgTurnExecutionService service =
                new TrpgTurnExecutionService(
                        conversationService,
                        lockService,
                        planResolver,
                        mock(GroupRuntimeRegistry.class),
                        turnMapper,
                        stepMapper,
                        mock(GroupChatMessageMapper.class),
                        mock(GroupTurnRecoveryService.class),
                        groupChatService,
                        immediateTransactionTemplate(),
                        mock(TrpgSceneSelectionService.class),
                        mock(TrpgSceneLifecycleService.class),
                        mock(TrpgSceneSelectionStore.class),
                        mock(TrpgParticipantService.class),
                        decisionStore,
                        mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                        mock(com.me.galchat.groupchat.dice
                                .DiceRollMessageCodec.class),
                        combatLifecycleService,
                        mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                        checkpointService,
                        mock(TrpgUnconsciousRecoveryService.class),
                        mock(ITrpgSaveService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep first = new GroupChatReplyStep()
                .setId(102L).setTurnId(101L).setStepNo(1)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setOutputMessageId(202L)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        GroupChatReplyStep failed = new GroupChatReplyStep()
                .setId(103L).setTurnId(101L).setStepNo(2)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                .setSpeakerId(9L)
                .setOutputMessageId(203L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep blocked = new GroupChatReplyStep()
                .setId(104L).setTurnId(101L).setStepNo(3)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_BLOCKED);
        List<GroupChatReplyStep> allSteps =
                List.of(first, failed, blocked);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectById(101L)).thenReturn(turn);
        when(stepMapper.selectById(102L)).thenReturn(first);
        when(stepMapper.selectById(103L)).thenReturn(failed);
        when(stepMapper.selectById(104L)).thenReturn(blocked);
        when(stepMapper.selectList(any())).thenReturn(allSteps);
        when(checkpointService.restore(turn, failed))
                .thenAnswer(invocation -> {
                    allSteps.forEach(step -> step
                            .setStatus(GroupChatConstant.STATUS_PENDING)
                            .setOutputMessageId(null)
                            .setErrorMessage(null));
                    turn.setStatus(GroupChatConstant.STATUS_RUNNING);
                    return false;
                });
        when(groupChatService.streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                org.mockito.ArgumentMatchers.eq(turn),
                any(GroupChatReplyStep.class)))
                .thenReturn(Flux.empty());

        List<GroupChatEvent> events = service.retry(
                7L, 101L, 103L).collectList().block();

        assertThat(events)
                .extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_COMPLETED);
        verify(groupChatService).streamPersistedStep(
                conversation, turn, first);
        verify(groupChatService).streamPersistedStep(
                conversation, turn, failed);
        verify(groupChatService).streamPersistedStep(
                conversation, turn, blocked);
        allSteps.forEach(step -> {
            verify(decisionStore).deleteByReplyStepId(step.getId());
            verify(combatLifecycleService)
                    .clearControlMarkersForRetry(step.getId());
        });
    }

    @Test
    void firstSceneRoundContainsOnlyTheKpIntroduction() {
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
                transactionTemplate,
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
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
        List<GroupChatReplyStep> insertedSteps =
                new java.util.ArrayList<>();
        when(stepMapper.insert(any(GroupChatReplyStep.class)))
                .thenAnswer(invocation -> {
            GroupChatReplyStep inserted = invocation.getArgument(0);
            inserted.setId(ids.incrementAndGet());
            insertedSteps.add(inserted);
            return 1;
        });
        when(stepMapper.selectList(any()))
                .thenAnswer(invocation -> List.copyOf(insertedSteps));
        when(groupChatService.streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                any(GroupChatTurn.class),
                any(GroupChatReplyStep.class)))
                .thenReturn(Flux.empty());

        GroupTurnContinueDTO request = new GroupTurnContinueDTO();
        request.setClientRequestId("start-1");
        List<GroupChatEvent> events =
                service.continueTurn(7L, request).collectList().block();

        assertThat(events)
                .extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_COMPLETED);
        verify(groupChatService, never()).streamPersistedStep(
                org.mockito.ArgumentMatchers.eq(conversation),
                any(GroupChatTurn.class),
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
                        GroupChatConstant.STATUS_COMPLETED);
        var stepCaptor = org.mockito.ArgumentCaptor.forClass(
                GroupChatReplyStep.class);
        verify(stepMapper)
                .insert(stepCaptor.capture());
        assertThat(stepCaptor.getValue())
                .extracting(
                        GroupChatReplyStep::getActionType,
                        GroupChatReplyStep::getSpeakerType,
                        GroupChatReplyStep::getSpeakerId,
                        GroupChatReplyStep::getStatus)
                .containsExactly(
                        GroupChatConstant.ACTION_TRPG_SCENE_INTRO,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        GroupChatConstant.STATUS_PENDING);
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
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
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
        request.setClientRequestId("action-1");
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
                        GroupChatMessage::getClientRequestId,
                        GroupChatMessage::getSequenceNo)
                .containsExactly(
                        101L, 102L,
                        GroupChatConstant.ACTOR_USER, 501L,
                        "我检查餐桌下面。", "action-1", 9L);
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
    void continueRecreatesSceneSelectionWhenWaitingOptionsExpired() {
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
        TrpgSceneSelectionStore selectionStore =
                mock(TrpgSceneSelectionStore.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService,
                lockService,
                planResolver,
                runtimeRegistry,
                turnMapper,
                stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(GroupChatService.class),
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                selectionStore,
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn expiredTurn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setPlanSource(
                        GroupChatConstant.TURN_SOURCE_SCENE_SELECTION)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep expiredStep = new GroupChatReplyStep()
                .setId(102L)
                .setTurnId(101L)
                .setStepNo(2)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_SCENE_SELECTION)
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
        when(turnMapper.selectList(any()))
                .thenReturn(List.of(expiredTurn));
        when(stepMapper.selectList(any()))
                .thenReturn(List.of(expiredStep), List.of());
        when(selectionStore.getOptions(7L, 101L))
                .thenReturn(java.util.Map.of());
        when(runtimeRegistry.require(GroupChatConstant.MODE_TRPG))
                .thenReturn(runtime);
        when(planResolver.resolve(conversation, runtime))
                .thenReturn(new GroupTurnPlanResolver.ResolvedTurnPlan(
                        GroupChatConstant.TURN_SOURCE_SCENE_SELECTION,
                        null,
                        List.of(new GroupActionSpec(
                                GroupChatConstant
                                        .ACTION_TRPG_SCENE_SELECTION,
                                GroupChatConstant.ACTOR_KP,
                                null,
                                "scene-selection",
                                "选择场景",
                                1,
                                1))));
        AtomicLong ids = new AtomicLong(200L);
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

        List<GroupChatEvent> events = service.continueTurn(
                7L, new GroupTurnContinueDTO()).collectList().block();

        assertThat(events)
                .extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_COMPLETED);
        assertThat(expiredTurn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_FAILED);
        assertThat(expiredStep.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_FAILED);
        verify(selectionStore).clear(7L);
        verify(turnMapper).insert(any(GroupChatTurn.class));
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
                groupChatService, immediateTransactionTemplate(),
                selectionService,
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
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
        request.setClientRequestId("selection-1");
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
                                                .equals(102L)
                                        && "selection-1".equals(
                                        message.getClientRequestId())));
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
                groupChatService, immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                lifecycleService,
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
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

        GroupEndExplorationDTO request = new GroupEndExplorationDTO();
        request.setClientRequestId("end-1");
        service.endExploration(
                7L, 101L, 102L, request).collectList().block();

        verify(lifecycleService).requestInvestigatorFinish(
                7L, 102L, GroupChatConstant.ACTOR_USER, 501L);
        verify(groupChatService).streamPersistedStep(
                conversation, turn, kp);
        verify(messageMapper).insert(
                org.mockito.ArgumentMatchers.argThat(
                        (GroupChatMessage message) ->
                                message.getContent().contains("结束当前场景探索")
                                        && "end-1".equals(
                                        message.getClientRequestId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "trpg"})
    void currentTurnExposesReplyOrderForSupportedModes(String mode) {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService,
                mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class),
                turnMapper,
                stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(GroupChatService.class),
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(mode);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(102L)
                .setTurnId(101L)
                .setActionType(GroupChatConstant.ACTION_CHAT_REPLY)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                .setItemOrder(1)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(stepMapper.selectList(any())).thenReturn(List.of(step));

        GroupCurrentTurnVO current = service.current(7L);

        assertThat(current.turnId()).isEqualTo(101L);
        assertThat(current.status())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_INPUT);
        assertThat(current.itemOrder()).isEqualTo(1);
    }

    @Test
    void currentTurnExposesEveryStepAndDerivesCurrentStepFromRuntimeStatus() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService,
                mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class),
                turnMapper,
                stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(GroupChatService.class),
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setPlanId(201L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep completed = new GroupChatReplyStep()
                .setId(301L)
                .setTurnId(101L)
                .setItemOrder(1)
                .setStepNo(1)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                .setSpeakerId(11L)
                .setSubjectCharacterId(501L)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        GroupChatReplyStep waiting = new GroupChatReplyStep()
                .setId(302L)
                .setTurnId(101L)
                .setItemOrder(2)
                .setStepNo(2)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(22L)
                .setSubjectCharacterId(502L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep pending = new GroupChatReplyStep()
                .setId(303L)
                .setTurnId(101L)
                .setItemOrder(3)
                .setStepNo(3)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(stepMapper.selectList(any()))
                .thenReturn(List.of(completed, waiting, pending));

        GroupCurrentTurnVO current = service.current(7L);

        assertThat(current.stepId()).isEqualTo(302L);
        assertThat(current.itemOrder()).isEqualTo(2);
        assertThat(current.waitingForUser()).isTrue();
        assertThat(current.steps()).containsExactly(
                new com.me.galchat.domain.vo.GroupCurrentTurnStepVO(
                        301L, 1, GroupChatConstant.ACTOR_CHARACTER,
                        11L, 501L, GroupChatConstant.STATUS_COMPLETED,
                        null),
                new com.me.galchat.domain.vo.GroupCurrentTurnStepVO(
                        302L, 2, GroupChatConstant.ACTOR_USER,
                        22L, 502L, GroupChatConstant.STATUS_WAITING_INPUT,
                        null),
                new com.me.galchat.domain.vo.GroupCurrentTurnStepVO(
                        303L, 3, GroupChatConstant.ACTOR_KP,
                        null, null, GroupChatConstant.STATUS_PENDING,
                        null));
    }

    @Test
    void duplicateClientRequestIdRejectsContinueAndEveryUserActionBeforeMutation() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        TrpgSceneSelectionService selectionService =
                mock(TrpgSceneSelectionService.class);
        TrpgSceneLifecycleService lifecycleService =
                mock(TrpgSceneLifecycleService.class);
        TrpgTurnExecutionService service =
                new TrpgTurnExecutionService(
                        conversationService,
                        lockService,
                        mock(GroupTurnPlanResolver.class),
                        mock(GroupRuntimeRegistry.class),
                        turnMapper,
                        mock(GroupChatReplyStepMapper.class),
                        messageMapper,
                        mock(GroupTurnRecoveryService.class),
                        mock(GroupChatService.class),
                        immediateTransactionTemplate(),
                        selectionService,
                        lifecycleService,
                        mock(TrpgSceneSelectionStore.class),
                        mock(TrpgParticipantService.class),
                        mock(GroupAgentDecisionStore.class),
                        mock(com.me.galchat.mapper
                                .DiceRollSummaryMapper.class),
                        mock(com.me.galchat.groupchat.dice
                                .DiceRollMessageCodec.class),
                        mock(TrpgCombatLifecycleService.class),
                        mock(com.me.galchat.mapper
                                .GroupReplyPlanMapper.class),
                        mock(GroupTurnCheckpointService.class),
                        mock(TrpgUnconsciousRecoveryService.class),
                        mock(ITrpgSaveService.class));
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectCount(any())).thenReturn(1L);
        when(messageMapper.selectCount(any())).thenReturn(0L);

        GroupChatRequestDTO messageRequest =
                new GroupChatRequestDTO();
        messageRequest.setClientRequestId("duplicate-1");
        messageRequest.setContent("检查书桌");
        GroupSceneSelectionDTO selectionRequest =
                new GroupSceneSelectionDTO();
        selectionRequest.setClientRequestId("duplicate-1");
        selectionRequest.setOptionNo("2");
        GroupEndExplorationDTO endRequest =
                new GroupEndExplorationDTO();
        endRequest.setClientRequestId("duplicate-1");
        GroupTurnContinueDTO continueRequest =
                new GroupTurnContinueDTO();
        continueRequest.setClientRequestId("duplicate-1");

        assertDuplicateRejected(() -> service.continueTurn(
                7L, continueRequest).collectList().block());

        when(turnMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.selectCount(any())).thenReturn(1L);
        assertDuplicateRejected(() -> service.submitMessage(
                7L, 101L, 102L, messageRequest)
                .collectList().block());
        assertDuplicateRejected(() -> service.submitSelection(
                7L, 101L, 102L, selectionRequest)
                .collectList().block());
        assertDuplicateRejected(() -> service.endExploration(
                7L, 101L, 102L, endRequest)
                .collectList().block());

        verify(messageMapper, never())
                .insert(any(GroupChatMessage.class));
        verifyNoInteractions(selectionService, lifecycleService);
    }

    @Test
    void newCombatTurnSavesBeforeAdvancingRoundAndCreatingTurn() {
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
        TrpgCombatLifecycleService combatLifecycleService =
                mock(TrpgCombatLifecycleService.class);
        com.me.galchat.mapper.GroupReplyPlanMapper replyPlanMapper =
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class);
        ITrpgSaveService saveService = mock(ITrpgSaveService.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setActiveReplyPlanId(31L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn completed = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        GroupModeRuntime runtime = mock(GroupModeRuntime.class);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectList(any())).thenReturn(List.of(completed));
        when(stepMapper.selectList(any())).thenReturn(List.of());
        when(runtimeRegistry.require(GroupChatConstant.MODE_TRPG))
                .thenReturn(runtime);
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of());
        when(planResolver.resolve(conversation, runtime))
                .thenReturn(new GroupTurnPlanResolver.ResolvedTurnPlan(
                        GroupChatConstant.PLAN_SOURCE_COMBAT,
                        41L,
                        List.of()));
        when(replyPlanMapper.selectById(31L)).thenReturn(
                new com.me.galchat.domain.po.GroupReplyPlan()
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT));
        when(turnMapper.insert(any(GroupChatTurn.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatTurn>getArgument(0).setId(102L);
                    return 1;
                });
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService, lockService, planResolver,
                runtimeRegistry, turnMapper, stepMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(GroupChatService.class),
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                participantService,
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                combatLifecycleService, replyPlanMapper,
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                saveService);

        service.continueTurn(7L, new GroupTurnContinueDTO())
                .collectList().block();

        var order = org.mockito.Mockito.inOrder(
                saveService, combatLifecycleService, turnMapper);
        order.verify(saveService).saveBeforeTurn(conversation);
        order.verify(combatLifecycleService)
                .startNextRoundUnderLock(conversation);
        order.verify(turnMapper).insert(any(GroupChatTurn.class));
    }

    @Test
    void waitingTurnDoesNotOverwriteTheAutoCheckpoint() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        ITrpgSaveService saveService = mock(ITrpgSaveService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn waiting = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turnMapper.selectList(any())).thenReturn(List.of(waiting));
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversationService, lockService,
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class), turnMapper,
                mock(GroupChatReplyStepMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(GroupChatService.class),
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                saveService);

        assertThatThrownBy(() -> service.continueTurn(
                7L, new GroupTurnContinueDTO()).collectList().block())
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("等待用户调查员行动");

        verifyNoInteractions(saveService);
    }

    @Test
    void currentTurnPrioritizesHumanInteractionChild() {
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper steps =
                mock(GroupChatReplyStepMapper.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L).setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep parent = new GroupChatReplyStep()
                .setId(201L).setTurnId(101L).setStepNo(4)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION);
        GroupChatReplyStep child = new GroupChatReplyStep()
                .setId(301L).setTurnId(101L).setStepNo(8)
                .setParentStepId(201L).setRootStepId(201L)
                .setInteractionType("TEAM_RISK_CONFIRMATION")
                .setInteractionSeq(2).setPromptMessageId(401L)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(31L).setSubjectCharacterId(31L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        when(conversations.requireAuthorized(7L))
                .thenReturn(conversation);
        when(turns.selectList(any())).thenReturn(List.of(turn));
        when(steps.selectList(any())).thenReturn(List.of(parent, child));
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversations, mock(GroupConversationLockService.class),
                mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class), turns, steps,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class),
                mock(GroupChatService.class),
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));

        GroupCurrentTurnVO current = service.current(7L);

        assertThat(current.stepId()).isEqualTo(301L);
        assertThat(current.inputType()).isEqualTo("clarification");
        assertThat(current.promptMessageId()).isEqualTo(401L);
        assertThat(current.interactionType())
                .isEqualTo("TEAM_RISK_CONFIRMATION");
        assertThat(current.interactionSeq()).isEqualTo(2);
        assertThat(current.waitingForUser()).isTrue();
    }

    @Test
    void kpClarificationPausesAtHumanChildInsteadOfCompletingTurn() {
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupConversationLockService locks =
                mock(GroupConversationLockService.class);
        GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper steps =
                mock(GroupChatReplyStepMapper.class);
        GroupChatService chats = mock(GroupChatService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L).setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
        GroupChatReplyStep parent = new GroupChatReplyStep()
                .setId(201L).setTurnId(101L).setStepNo(4)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        GroupChatReplyStep child = new GroupChatReplyStep()
                .setId(301L).setTurnId(101L).setStepNo(8)
                .setParentStepId(201L).setRootStepId(201L)
                .setInteractionType("KP_CLARIFICATION")
                .setInteractionSeq(1).setPromptMessageId(401L)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(31L).setStatus(GroupChatConstant.STATUS_PENDING);
        when(conversations.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversations.requireActive(7L))
                .thenReturn(conversation);
        when(locks.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turns.selectList(any())).thenReturn(List.of(turn));
        when(turns.selectById(101L)).thenReturn(turn);
        when(steps.selectCount(any())).thenReturn(0L);
        when(steps.selectList(any()))
                .thenReturn(List.of(parent), List.of(child));
        when(steps.selectById(201L)).thenReturn(parent);
        when(chats.streamPersistedStep(conversation, turn, parent))
                .thenReturn(Flux.defer(() -> {
                    parent.setStatus(
                            GroupChatConstant.STATUS_WAITING_INTERACTION);
                    return Flux.empty();
                }));
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversations, locks, mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class), turns, steps,
                mock(GroupChatMessageMapper.class),
                mock(GroupTurnRecoveryService.class), chats,
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));

        List<GroupChatEvent> events = service.continueTurn(
                7L, new GroupTurnContinueDTO()).collectList().block();

        assertThat(events).extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_WAITING_INPUT);
        assertThat(events.getLast().getReplyStepId()).isEqualTo(301L);
        assertThat(child.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_INPUT);
        assertThat(turn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_INPUT);
    }

    @Test
    void humanClarificationAnswerResumesOriginalKpStep() {
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupConversationLockService locks =
                mock(GroupConversationLockService.class);
        GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper steps =
                mock(GroupChatReplyStepMapper.class);
        GroupChatMessageMapper messages =
                mock(GroupChatMessageMapper.class);
        GroupChatService chats = mock(GroupChatService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L).setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        GroupChatReplyStep parent = new GroupChatReplyStep()
                .setId(201L).setTurnId(101L).setStepNo(4)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION);
        GroupChatReplyStep child = new GroupChatReplyStep()
                .setId(301L).setTurnId(101L).setStepNo(8)
                .setParentStepId(201L).setRootStepId(201L)
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(31L)
                .setStatus(GroupChatConstant.STATUS_WAITING_INPUT);
        when(conversations.requireAuthorized(7L))
                .thenReturn(conversation);
        when(conversations.requireActive(7L))
                .thenReturn(conversation);
        when(conversations.nextSequence(7L)).thenReturn(9L);
        when(locks.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(turns.selectById(101L)).thenReturn(turn);
        when(steps.selectById(301L)).thenReturn(child);
        when(steps.selectById(201L)).thenReturn(parent);
        when(steps.selectList(any()))
                .thenReturn(List.of(parent));
        when(messages.insert(any(GroupChatMessage.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupChatMessage>getArgument(0).setId(401L);
                    return 1;
                });
        when(chats.streamPersistedStep(conversation, turn, parent))
                .thenReturn(Flux.defer(() -> {
                    parent.setStatus(GroupChatConstant.STATUS_COMPLETED);
                    return Flux.empty();
                }));
        TrpgTurnExecutionService service = new TrpgTurnExecutionService(
                conversations, locks, mock(GroupTurnPlanResolver.class),
                mock(GroupRuntimeRegistry.class), turns, steps, messages,
                mock(GroupTurnRecoveryService.class), chats,
                immediateTransactionTemplate(),
                mock(TrpgSceneSelectionService.class),
                mock(TrpgSceneLifecycleService.class),
                mock(TrpgSceneSelectionStore.class),
                mock(TrpgParticipantService.class),
                mock(GroupAgentDecisionStore.class),
                mock(com.me.galchat.mapper.DiceRollSummaryMapper.class),
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class),
                mock(GroupTurnCheckpointService.class),
                mock(TrpgUnconsciousRecoveryService.class),
                mock(ITrpgSaveService.class));
        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setContent("改查上锁的抽屉；打不开就停手。");

        List<GroupChatEvent> events = service.submitMessage(
                7L, 101L, 301L, request).collectList().block();

        verify(chats).streamPersistedStep(conversation, turn, parent);
        assertThat(events).extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_TURN_ACCEPTED,
                        GroupChatConstant.EVENT_TURN_COMPLETED);
        assertThat(child.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_COMPLETED);
    }

    private void assertDuplicateRejected(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        com.me.galchat.exception
                                .UserRequestException.class)
                .hasMessageContaining(
                        "clientRequestId已处理");
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
