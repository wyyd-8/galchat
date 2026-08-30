package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.GroupChatMessageVO;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupModelInvocation;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.groupchat.runtime.trpg.DecisionActionStreamParser;
import com.me.galchat.groupchat.tool.GroupToolContextFactory;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class GroupChatService {

    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupTurnPlanResolver turnPlanResolver;
    private final GroupRuntimeRegistry runtimeRegistry;
    private final GroupChatMessageMapper messageMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupTurnRecoveryService recoveryService;
    private final GroupToolContextFactory toolContextFactory;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final TransactionTemplate transactionTemplate;
    private final DiceRollMessageCodec diceMessageCodec;
    private final ObjectMapper objectMapper;
    private final GroupMaterialMessageFeed materialMessageFeed;
    private final TrpgSceneSelectionService sceneSelectionService;
    private final GroupAgentDecisionStore decisionStore;
    private final TrpgCombatLifecycleService combatLifecycleService;
    private final GroupTurnCheckpointService checkpointService;
    private final GroupActorRuntimeService actorRuntimeService;

    @Autowired
    public GroupChatService(GroupConversationService conversationService,
                            GroupConversationLockService lockService,
                            GroupTurnPlanResolver turnPlanResolver,
                            GroupRuntimeRegistry runtimeRegistry,
                            GroupChatMessageMapper messageMapper,
                            GroupChatTurnMapper turnMapper,
                            GroupChatReplyStepMapper stepMapper,
                            GroupTurnRecoveryService recoveryService,
                            GroupToolContextFactory toolContextFactory,
                            IUserWorldPrefixService userWorldPrefixService,
                            TransactionTemplate transactionTemplate,
                            DiceRollMessageCodec diceMessageCodec,
                            ObjectMapper objectMapper,
                            GroupMaterialMessageFeed materialMessageFeed,
                            TrpgSceneSelectionService
                                    sceneSelectionService,
                            GroupAgentDecisionStore decisionStore,
                            TrpgCombatLifecycleService
                                    combatLifecycleService,
                            GroupTurnCheckpointService
                                    checkpointService,
                            GroupActorRuntimeService actorRuntimeService) {
        this.conversationService = conversationService;
        this.lockService = lockService;
        this.turnPlanResolver = turnPlanResolver;
        this.runtimeRegistry = runtimeRegistry;
        this.messageMapper = messageMapper;
        this.turnMapper = turnMapper;
        this.stepMapper = stepMapper;
        this.recoveryService = recoveryService;
        this.toolContextFactory = toolContextFactory;
        this.userWorldPrefixService = userWorldPrefixService;
        this.transactionTemplate = transactionTemplate;
        this.diceMessageCodec = diceMessageCodec;
        this.objectMapper = objectMapper;
        this.materialMessageFeed = materialMessageFeed;
        this.sceneSelectionService = sceneSelectionService;
        this.decisionStore = decisionStore;
        this.combatLifecycleService = combatLifecycleService;
        this.checkpointService = checkpointService;
        this.actorRuntimeService = actorRuntimeService;
    }

    public Flux<GroupChatEvent> chat(Long conversationId, GroupChatRequestDTO request) {
        return Flux.defer(() -> {
            validateRequest(request);
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException("当前群聊正在生成回复，请稍后再试"));
            }

            try {
                GroupConversation conversation = conversationService.requireActive(conversationId);
                if (GroupChatConstant.MODE_TRPG.equals(
                        conversation.getMode())) {
                    throw new UserRequestException(
                            "TRPG群聊请使用行动轮接口");
                }
                recoveryService.recoverInterrupted(conversationId);
                Long activeTurns = turnMapper
                        .countNonTerminalByConversationId(conversationId);
                if (activeTurns != null && activeTurns > 0) {
                    throw new UserRequestException(
                            "当前群聊正等待角色人工输出，请先完成该角色发言");
                }
                GroupModeRuntime runtime = runtimeRegistry.require(conversation.getMode());
                PreparedTurn prepared = transactionTemplate.execute(
                        status -> prepareTurn(conversation, request, runtime));
                if (prepared == null) {
                    throw new UserRequestException("创建群聊轮次失败");
                }
                try {
                    runtime.contextPolicy().onTurnStarted(conversation, prepared.userMessage());
                } catch (RuntimeException e) {
                    recoveryService.recoverInterrupted(conversationId);
                    return Flux.just(failureEvent(conversationId, prepared.turn().getId(), e))
                            .doFinally(signal -> lockService.unlock(lock));
                }
                Flux<GroupChatEvent> accepted = Flux.just(GroupChatEvent.builder()
                        .eventType(GroupChatConstant.EVENT_TURN_ACCEPTED)
                        .conversationId(conversationId)
                        .turnId(prepared.turn().getId())
                        .messageId(prepared.userMessage().getId())
                        .sequence(prepared.userMessage().getSequenceNo())
                        .build());
                Flux<GroupChatEvent> replies = executeChatActions(
                        runtime, conversation, prepared.turn(),
                        prepared.actions(), 0);
                return Flux.concat(accepted, replies)
                        .doOnError(error -> recoveryService.recoverInterrupted(conversationId))
                        .onErrorResume(error -> Flux.just(
                                failureEvent(conversationId, prepared.turn().getId(), error)))
                        .doFinally(signal -> {
                            if (signal == SignalType.CANCEL) {
                                transactionTemplate.executeWithoutResult(status -> {
                                    recoveryService.cancelPendingSteps(
                                            prepared.turn().getId(), "客户端取消生成");
                                    cancelTurn(prepared.turn());
                                });
                            }
                            lockService.unlock(lock);
                        });
            } catch (RuntimeException e) {
                lockService.unlock(lock);
                return Flux.error(e);
            }
        });
    }

    private Flux<GroupChatEvent> executeChatActions(
            GroupModeRuntime runtime,
            GroupConversation conversation,
            GroupChatTurn turn,
            List<PreparedAction> actions,
            int index) {
        return Flux.defer(() -> {
            if (index >= actions.size()) {
                completeTurn(turn);
                turnPlanResolver.onTurnCompleted(
                        conversation, turn.getPlanSource());
                return Flux.just(GroupChatEvent.builder()
                        .eventType(GroupChatConstant.EVENT_TURN_COMPLETED)
                        .conversationId(conversation.getId())
                        .turnId(turn.getId())
                        .build());
            }
            PreparedAction prepared = actions.get(index);
            if (isManualStep(conversation, prepared.step())) {
                return waitForManualInput(
                        conversation, turn, prepared.step());
            }
            return executeStep(runtime, conversation, turn, prepared)
                    .concatWith(executeChatActions(
                            runtime, conversation, turn,
                            actions, index + 1));
        });
    }

    public List<GroupChatMessageVO> listHistory(Long conversationId, Long beforeId, Integer requestedSize) {
        GroupConversation conversation = conversationService.requireAuthorized(conversationId);
        int size = requestedSize == null ? GroupChatConstant.DEFAULT_HISTORY_PAGE_SIZE : requestedSize;
        if (size <= 0 || size > 200) {
            throw new UserRequestException("查询条数必须在1到200之间");
        }
        List<GroupChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversationId)
                .eq(GroupChatMessage::getVisibility, "public")
                .lt(beforeId != null, GroupChatMessage::getId, beforeId)
                .orderByDesc(GroupChatMessage::getId)
                .last("limit " + size));
        Collections.reverse(messages);
        if (messages.isEmpty()) {
            return List.of();
        }
        List<Long> replyStepIds = messages.stream()
                .map(GroupChatMessage::getReplyStepId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, String> decisions =
                decisionStore.contentByReplyStepIds(replyStepIds);
        Map<Long, Long> outputMessageIdsByStep = new HashMap<>();
        List<GroupChatReplyStep> replySteps =
                stepMapper.selectBatchIds(replyStepIds);
        if (replySteps != null) {
            for (GroupChatReplyStep replyStep : replySteps) {
                outputMessageIdsByStep.put(
                        replyStep.getId(),
                        replyStep.getOutputMessageId());
            }
        }

        return messages.stream().map(message -> new GroupChatMessageVO(
                message.getId(), message.getConversationId(), message.getTurnId(), message.getReplyStepId(),
                message.getSpeakerType(), message.getSpeakerId(), speakerName(conversation, message),
                message.getMessageKind(), message.getContent(),
                message.getReplyStepId() == null
                        || !message.getId().equals(
                        outputMessageIdsByStep.get(
                                message.getReplyStepId()))
                        ? null : decisions.get(message.getReplyStepId()),
                message.getSequenceNo(), message.getStatus(), message.getCreatedAt())).toList();
    }

    public Flux<GroupChatEvent> submitManualMessage(
            Long conversationId,
            Long turnId,
            Long stepId,
            GroupChatRequestDTO request) {
        return Flux.defer(() -> {
            validateRequest(request);
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行回复，请稍后再试"));
            }
            try {
                GroupConversation conversation =
                        conversationService.requireActive(conversationId);
                if (!GroupChatConstant.MODE_CHAT.equals(
                        conversation.getMode())) {
                    throw new UserRequestException(
                            "跑团人工输出请使用行动输入接口");
                }
                assertManualRequestIdAvailable(
                        conversationId, request.getClientRequestId());
                GroupChatTurn turn = requireManualTurn(
                        conversationId, turnId);
                GroupChatReplyStep step = requireManualStep(
                        turnId, stepId);
                GroupChatMessage message = transactionTemplate.execute(
                        status -> completeManualStep(
                                conversation, turn, step, request));
                if (message == null) {
                    throw new UserRequestException("保存人工输出失败");
                }
                List<GroupChatReplyStep> remaining =
                        stepMapper.selectList(
                                new LambdaQueryWrapper<
                                        GroupChatReplyStep>()
                                        .eq(GroupChatReplyStep::getTurnId,
                                                turnId)
                                        .gt(GroupChatReplyStep::getStepNo,
                                                step.getStepNo())
                                        .eq(GroupChatReplyStep::getStatus,
                                                GroupChatConstant
                                                        .STATUS_PENDING)
                                        .orderByAsc(
                                                GroupChatReplyStep
                                                        ::getStepNo));
                List<PreparedAction> actions = remaining == null
                        ? List.of()
                        : remaining.stream()
                        .map(this::toPreparedAction)
                        .toList();
                GroupModeRuntime runtime = runtimeRegistry.require(
                        conversation.getMode());
                GroupChatEvent.Speaker speaker =
                        GroupChatEvent.Speaker.builder()
                                .type(step.getSpeakerType())
                                .id(step.getSpeakerId())
                                .name(runtime.agentPolicy().actorName(
                                        conversation.getUserWorldId(),
                                        new GroupActorRef(
                                                step.getSpeakerType(),
                                                step.getSpeakerId())))
                                .build();
                Flux<GroupChatEvent> accepted = Flux.just(
                        GroupChatEvent.builder()
                                .eventType(GroupChatConstant
                                        .EVENT_TURN_ACCEPTED)
                                .conversationId(conversationId)
                                .turnId(turnId)
                                .replyStepId(stepId)
                                .messageId(message.getId())
                                .sequence(message.getSequenceNo())
                                .build(),
                        baseEvent(
                                GroupChatConstant.EVENT_MESSAGE_COMPLETED,
                                conversation, turn, step, message, speaker)
                                .content(message.getContent())
                                .build());
                return Flux.concat(
                                accepted,
                                executeChatActions(runtime, conversation,
                                        turn, actions, 0))
                        .doOnError(error -> recoveryService
                                .recoverInterrupted(conversationId))
                        .doFinally(signal -> lockService.unlock(lock));
            } catch (RuntimeException exception) {
                lockService.unlock(lock);
                return Flux.error(exception);
            }
        });
    }

    private GroupChatTurn requireManualTurn(
            Long conversationId, Long turnId) {
        GroupChatTurn turn = turnMapper.selectById(turnId);
        if (turn == null
                || !conversationId.equals(turn.getConversationId())) {
            throw new UserRequestException("群聊回复轮次不存在");
        }
        if (!GroupChatConstant.STATUS_WAITING_INPUT.equals(
                turn.getStatus())) {
            throw new UserRequestException("当前回复轮次不等待人工输出");
        }
        return turn;
    }

    private GroupChatReplyStep requireManualStep(
            Long turnId, Long stepId) {
        GroupChatReplyStep step = stepMapper.selectById(stepId);
        if (step == null || !turnId.equals(step.getTurnId())) {
            throw new UserRequestException("人工输出步骤不存在");
        }
        if (!GroupChatConstant.ACTOR_CHARACTER.equals(
                step.getSpeakerType())
                || !GroupChatConstant.CONTROL_MANUAL.equals(
                step.getExecutionMode())
                || !GroupChatConstant.STATUS_WAITING_INPUT.equals(
                step.getStatus())) {
            throw new UserRequestException("当前步骤不等待人工输出");
        }
        return step;
    }

    private GroupChatMessage completeManualStep(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step,
            GroupChatRequestDTO request) {
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setTurnId(turn.getId())
                .setReplyStepId(step.getId())
                .setClientRequestId(request.getClientRequestId() == null
                        ? null : request.getClientRequestId().trim())
                .setSpeakerType(step.getSpeakerType())
                .setSpeakerId(step.getSpeakerId())
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setVisibility("public")
                .setContent(request.getContent().trim())
                .setSequenceNo(conversationService.nextSequence(
                        conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
        step.setOutputMessageId(message.getId())
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setUpdatedAt(now);
        stepMapper.updateById(step);
        turn.setStatus(GroupChatConstant.STATUS_RUNNING)
                .setUpdatedAt(now);
        turnMapper.updateById(turn);
        return message;
    }

    private void assertManualRequestIdAvailable(
            Long conversationId, String clientRequestId) {
        if (!StringUtils.hasText(clientRequestId)) {
            return;
        }
        Long count = messageMapper.selectCount(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId,
                                conversationId)
                        .eq(GroupChatMessage::getClientRequestId,
                                clientRequestId.trim()));
        if (count != null && count > 0) {
            throw new UserRequestException(
                    "clientRequestId已处理，请勿重复提交");
        }
    }

    private PreparedAction toPreparedAction(GroupChatReplyStep step) {
        return new PreparedAction(new GroupActionSpec(
                step.getActionType(), step.getSpeakerType(),
                step.getSpeakerId(), step.getSubjectCharacterId(),
                step.getGroupKey(), step.getGroupName(),
                step.getGroupOrder(), step.getItemOrder(),
                step.getInteractionType()), step);
    }

    private PreparedTurn prepareTurn(GroupConversation conversation, GroupChatRequestDTO request,
                                     GroupModeRuntime runtime) {
        if (StringUtils.hasText(request.getClientRequestId())) {
            Long duplicateCount = turnMapper.selectCount(new LambdaQueryWrapper<GroupChatTurn>()
                    .eq(GroupChatTurn::getConversationId, conversation.getId())
                    .eq(GroupChatTurn::getClientRequestId, request.getClientRequestId()));
            if (duplicateCount != null && duplicateCount > 0) {
                throw new UserRequestException("clientRequestId已处理，请勿重复提交");
            }
        }

        GroupTurnPlanResolver.ResolvedTurnPlan resolved =
                turnPlanResolver.resolve(conversation, runtime);
        List<GroupActionSpec> actions = resolved.actions();
        if (actions.size() > GroupChatConstant.MAX_REPLY_STEPS) {
            throw new UserRequestException("单次回复人物数量不能超过" + GroupChatConstant.MAX_REPLY_STEPS);
        }
        for (GroupActionSpec action : actions) {
            conversationService.checkReplyMember(
                    conversation.getId(), action.actorType(), action.actorId(), false);
        }

        LocalDateTime now = LocalDateTime.now();
        GroupChatTurn turn = new GroupChatTurn()
                .setConversationId(conversation.getId())
                .setClientRequestId(StringUtils.hasText(request.getClientRequestId())
                        ? request.getClientRequestId().trim() : null)
                .setPlanSource(resolved.source())
                .setPlanContextId(resolved.contextId())
                .setStatus(GroupChatConstant.STATUS_PENDING)
                .setRevision(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        turnMapper.insert(turn);

        GroupChatMessage userMessage = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setSceneId(sceneId(turn))
                .setTurnId(turn.getId())
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setVisibility("public")
                .setContent(request.getContent().trim())
                .setSequenceNo(conversationService.nextSequence(conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(userMessage);
        turn.setTriggerMessageId(userMessage.getId()).setStatus(GroupChatConstant.STATUS_RUNNING);
        turnMapper.updateById(turn);

        List<PreparedAction> preparedActions = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            GroupActionSpec action = actions.get(i);
            GroupChatReplyStep step = new GroupChatReplyStep()
                    .setTurnId(turn.getId())
                    .setGroupKey(action.groupKey())
                    .setGroupName(action.groupName())
                    .setGroupOrder(action.groupOrder())
                    .setItemOrder(action.itemOrder())
                    .setStepNo(i + 1)
                    .setActionType(action.actionType())
                    .setSpeakerType(action.actorType())
                    .setSpeakerId(action.actorId())
                    .setSubjectCharacterId(action.subjectCharacterId())
                    .setForceReply(false)
                    .setStatus(GroupChatConstant.STATUS_PENDING)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            stepMapper.insert(step);
            preparedActions.add(new PreparedAction(action, step));
        }
        return new PreparedTurn(turn, userMessage, preparedActions);
    }

    private Flux<GroupChatEvent> executeStep(GroupModeRuntime runtime, GroupConversation conversation,
                                             GroupChatTurn turn, PreparedAction preparedAction) {
        GroupActionSpec action = preparedAction.action();
        GroupChatReplyStep step = preparedAction.step();
        GenerationAccumulator accumulator = new GenerationAccumulator(
                usesDecisionActionProtocol(conversation, action));
        FinalizationGuard finalizationGuard = new FinalizationGuard();
        AtomicReference<GroupChatMessage> outputRef = new AtomicReference<>();
        return Flux.defer(() -> {
            if (shouldSkipStep(step)) {
                return Flux.empty();
            }
            if (GroupChatConstant.MODE_TRPG.equals(
                    conversation.getMode())) {
                checkpointService.initializeStep(turn, step);
            }
            GroupContextMaterial context = runtime.contextPolicy().load(conversation, action);
            GroupModelInvocation invocation = runtime.agentPolicy().prepare(conversation, action, context);
            String speakerName = runtime.agentPolicy()
                    .actorName(conversation.getUserWorldId(),
                            new GroupActorRef(step.getSpeakerType(), step.getSpeakerId()));
            GroupChatEvent.Speaker speaker = GroupChatEvent.Speaker.builder()
                    .type(step.getSpeakerType()).id(step.getSpeakerId()).name(speakerName).build();
            UserWorldPrefix userWorld = userWorldPrefixService.getById(conversation.getUserWorldId());
            String favorSystemStatus = userWorld == null ? null : userWorld.getFavorSystemStatus();
            ChatClient selectedClient = actorRuntimeService.chatClient(
                    conversation, step, invocation.chatClient());
            ChatClient.ChatClientRequestSpec requestSpec = selectedClient.prompt(invocation.prompt())
                    .toolContext(toolContextFactory.create(
                            conversation, action, turn.getId(),
                            step.getId(), favorSystemStatus,
                            userWorld == null
                                    ? null : userWorld.getUserId()));
            if (!invocation.tools().isEmpty()) {
                requestSpec = requestSpec.tools(invocation.tools().toArray());
            }
            if (isBufferedInvestigatorSelection(action)) {
                return executeBufferedInvestigatorSelection(
                        requestSpec, conversation, turn, step, speaker);
            }
            if (GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE.equals(
                    action.actionType())) {
                return executeBufferedCombatRoute(
                        requestSpec, conversation, turn, step, speaker);
            }
            GroupChatMessage outputMessage = transactionTemplate.execute(status -> {
                step.setStatus(GroupChatConstant.STATUS_RUNNING).setUpdatedAt(LocalDateTime.now());
                stepMapper.updateById(step);
                return createStreamingMessage(conversation, turn, step);
            });
            if (outputMessage == null) {
                throw new IllegalStateException("创建群聊回复消息失败");
            }
            outputRef.set(outputMessage);
            Flux<GroupChatEvent> started = Flux.just(baseEvent(GroupChatConstant.EVENT_REPLY_STARTED,
                    conversation, turn, step, outputMessage, speaker).build());
            Flux<GroupChatEvent> deltas = requestSpec.stream().chatResponse()
                    .flatMapIterable(response -> toEvents(response, conversation, turn, step, outputMessage,
                            speaker, accumulator));
            Flux<GroupChatEvent> finished = Flux.defer(() -> {
                if (accumulator.interaction == null) {
                    accumulator.finishDecisionAction();
                }
                accumulator.assertValidOutput();
                finalizeCompletedStep(
                        conversation, turn, step, outputMessage,
                        accumulator, finalizationGuard);
                List<GroupChatEvent> events = new ArrayList<>(
                        materialEvents(conversation, turn, step, accumulator));
                events.add(baseEvent(GroupChatConstant.EVENT_MESSAGE_COMPLETED,
                        conversation, turn, step, outputMessage, speaker)
                        .content(accumulator.diceRoll == null
                                ? accumulator.content.toString() : null)
                        .build());
                return Flux.fromIterable(events);
            });
            return Flux.concat(started, deltas, finished);
        }).doOnError(error -> finalizeFailedStep(
                turn, step, outputRef.get(), accumulator, finalizationGuard, error))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        finalizeCancelledStep(turn, step, outputRef.get(), accumulator, finalizationGuard);
                    }
                });
    }

    private Flux<GroupChatEvent> executeBufferedCombatRoute(
            ChatClient.ChatClientRequestSpec requestSpec,
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step,
            GroupChatEvent.Speaker speaker) {
        transactionTemplate.executeWithoutResult(status -> {
            step.setStatus(GroupChatConstant.STATUS_RUNNING)
                    .setUpdatedAt(LocalDateTime.now());
            stepMapper.updateById(step);
        });
        return requestSpec.stream().chatResponse()
                .collectList()
                .flatMapMany(responses -> {
                    TrpgStepInteractionService.InteractionRequest
                            interaction = directInteraction(responses);
                    if (interaction != null) {
                        GroupChatMessage message =
                                persistBufferedInteraction(
                                        conversation, turn, step,
                                        interaction);
                        return Flux.just(
                                baseEvent(
                                        GroupChatConstant.EVENT_REPLY_STARTED,
                                        conversation, turn, step,
                                        message, speaker).build(),
                                baseEvent(
                                        GroupChatConstant
                                                .EVENT_MESSAGE_COMPLETED,
                                        conversation, turn, step,
                                        message, speaker)
                                        .content(interaction.question())
                                        .build());
                    }
                    StringBuilder raw = new StringBuilder();
                    responses.forEach(response -> {
                        if (response != null
                                && response.getResults() != null) {
                            response.getResults().forEach(generation -> {
                                if (generation.getOutput() != null
                                        && generation.getOutput()
                                        .getText() != null) {
                                    raw.append(generation.getOutput()
                                            .getText());
                                }
                            });
                        }
                    });
                    transactionTemplate.executeWithoutResult(status -> {
                        LocalDateTime now = LocalDateTime.now();
                        GroupChatMessage message =
                                new GroupChatMessage()
                                        .setConversationId(
                                                conversation.getId())
                                        .setTurnId(turn.getId())
                                        .setReplyStepId(step.getId())
                                        .setSpeakerType(
                                                GroupChatConstant.ACTOR_KP)
                                        .setMessageKind(
                                                GroupChatConstant
                                                        .MESSAGE_DIALOGUE)
                                        .setVisibility("internal")
                                        .setContent(raw.toString())
                                        .setSequenceNo(
                                                conversationService
                                                        .nextSequence(
                                                                conversation
                                                                        .getId()))
                                        .setStatus(
                                                GroupChatConstant
                                                        .STATUS_COMPLETED)
                                        .setCreatedAt(now)
                                        .setUpdatedAt(now);
                        messageMapper.insert(message);
                        combatLifecycleService.completeReactionRoute(
                                conversation, turn, step,
                                raw.toString());
                        step.setOutputMessageId(message.getId())
                                .setStatus(
                                        GroupChatConstant.STATUS_COMPLETED)
                                .setUpdatedAt(now);
                        stepMapper.updateById(step);
                        checkpointService.recordBoundary(
                                turn, step,
                                GroupTurnCheckpointService.COMPLETED);
                    });
                    return Flux.empty();
                });
    }

    private TrpgStepInteractionService.InteractionRequest
            directInteraction(List<ChatResponse> responses) {
        for (ChatResponse response : responses) {
            if (response == null || response.getResults() == null) {
                continue;
            }
            for (Generation generation : response.getResults()) {
                if (isDirectTool(generation, "askForClarification")) {
                    return readInteractionRequest(
                            generation.getOutput().getText());
                }
            }
        }
        return null;
    }

    private GroupChatMessage persistBufferedInteraction(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step,
            TrpgStepInteractionService.InteractionRequest interaction) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            GroupChatMessage message = new GroupChatMessage()
                    .setConversationId(conversation.getId())
                    .setSceneId(sceneId(turn))
                    .setTurnId(turn.getId())
                    .setReplyStepId(step.getId())
                    .setSpeakerType(GroupChatConstant.ACTOR_KP)
                    .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                    .setVisibility("public")
                    .setContent(interaction.question())
                    .setSequenceNo(conversationService.nextSequence(
                            conversation.getId()))
                    .setStatus(GroupChatConstant.STATUS_COMPLETED)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            messageMapper.insert(message);
            boolean orderedChild = step.getParentStepId() != null;
            step.setOutputMessageId(message.getId())
                    .setStatus(orderedChild
                            ? GroupChatConstant.STATUS_COMPLETED
                            : GroupChatConstant
                                    .STATUS_WAITING_INTERACTION)
                    .setUpdatedAt(now);
            stepMapper.updateById(step);
            stepMapper.update(null,
                    new LambdaUpdateWrapper<GroupChatReplyStep>()
                            .eq(GroupChatReplyStep::getId,
                                    interaction.childStepId())
                            .set(GroupChatReplyStep::getPromptMessageId,
                                    message.getId())
                            .set(GroupChatReplyStep::getUpdatedAt, now));
            if (orderedChild) {
                checkpointService.recordBoundary(
                        turn, step,
                        GroupTurnCheckpointService.COMPLETED);
            }
            return message;
        });
    }

    private boolean isBufferedInvestigatorSelection(
            GroupActionSpec action) {
        return GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                action.actionType())
                && GroupChatConstant.ACTOR_CHARACTER.equals(
                action.actorType());
    }

    private Flux<GroupChatEvent> executeBufferedInvestigatorSelection(
            ChatClient.ChatClientRequestSpec requestSpec,
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step,
            GroupChatEvent.Speaker speaker) {
        transactionTemplate.executeWithoutResult(status -> {
            step.setStatus(GroupChatConstant.STATUS_RUNNING)
                    .setUpdatedAt(LocalDateTime.now());
            stepMapper.updateById(step);
        });
        return requestSpec.stream().chatResponse()
                .collectList()
                .flatMapMany(responses -> {
                    BufferedSelectionOutput buffered =
                            readBufferedSelection(responses);
                    TrpgSceneSelectionService.SceneChoiceResult choice =
                            buffered.directResult() != null
                                    ? buffered.directResult()
                                    : sceneSelectionService.selectOption(
                                            conversation.getId(),
                                            turn.getId(),
                                            new GroupActorRef(
                                                    step.getSpeakerType(),
                                                    step.getSpeakerId()),
                                            buffered.pureNumber());
                    GroupChatMessage message =
                            persistCanonicalSelection(
                                    conversation, turn, step, choice);
                    GroupChatEvent choiceEvent = baseEvent(
                            GroupChatConstant.EVENT_SCENE_CHOICE_CREATED,
                            conversation, turn, step, message, speaker)
                            .sceneChoice(toEventChoice(choice))
                            .build();
                    GroupChatEvent completed = baseEvent(
                            GroupChatConstant.EVENT_MESSAGE_COMPLETED,
                            conversation, turn, step, message, speaker)
                            .content(message.getContent())
                            .sceneChoice(toEventChoice(choice))
                            .build();
                    return Flux.just(choiceEvent, completed);
                });
    }

    private BufferedSelectionOutput readBufferedSelection(
            List<ChatResponse> responses) {
        StringBuilder raw = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response == null || response.getResults() == null) {
                continue;
            }
            for (Generation generation : response.getResults()) {
                if (isDirectTool(
                        generation, "selectExplorationScene")) {
                    try {
                        return new BufferedSelectionOutput(
                                objectMapper.readValue(
                                        generation.getOutput().getText(),
                                        TrpgSceneSelectionService
                                                .SceneChoiceResult.class),
                                null);
                    } catch (JacksonException exception) {
                        throw new IllegalStateException(
                                "选景工具返回结果无法解析", exception);
                    }
                }
                if (generation.getOutput() != null
                        && generation.getOutput().getText() != null) {
                    raw.append(generation.getOutput().getText());
                }
            }
        }
        String text = raw.toString();
        String pureNumber = text.matches("\\s*\\d+\\s*")
                ? text.trim() : null;
        return new BufferedSelectionOutput(null, pureNumber);
    }

    private GroupChatMessage persistCanonicalSelection(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step,
            TrpgSceneSelectionService.SceneChoiceResult choice) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            String content = choice.controllerName() + ":"
                    + choice.investigatorName() + ":"
                    + choice.locationName();
            GroupChatMessage message = new GroupChatMessage()
                    .setConversationId(conversation.getId())
                    .setTurnId(turn.getId())
                    .setReplyStepId(step.getId())
                    .setSpeakerType(step.getSpeakerType())
                    .setSpeakerId(step.getSpeakerId())
                    .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                    .setVisibility("public")
                    .setContent(content)
                    .setSequenceNo(conversationService.nextSequence(
                            conversation.getId()))
                    .setStatus(GroupChatConstant.STATUS_COMPLETED)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            messageMapper.insert(message);
            step.setOutputMessageId(message.getId())
                    .setStatus(GroupChatConstant.STATUS_COMPLETED)
                    .setUpdatedAt(now);
            stepMapper.updateById(step);
            checkpointService.recordBoundary(
                    turn, step,
                    GroupTurnCheckpointService.COMPLETED);
            return message;
        });
    }

    private GroupChatEvent.SceneChoice toEventChoice(
            TrpgSceneSelectionService.SceneChoiceResult choice) {
        return GroupChatEvent.SceneChoice.builder()
                .optionNo(choice.optionNo())
                .controllerName(choice.controllerName())
                .investigatorName(choice.investigatorName())
                .locationName(choice.locationName())
                .randomized(choice.randomized())
                .build();
    }

    Flux<GroupChatEvent> streamPersistedStep(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step) {
        if (isManualStep(conversation, step)) {
            return waitForManualInput(conversation, turn, step);
        }
        GroupActionSpec action = new GroupActionSpec(
                step.getActionType(),
                step.getSpeakerType(),
                step.getSpeakerId(),
                step.getSubjectCharacterId(),
                step.getGroupKey(),
                step.getGroupName(),
                step.getGroupOrder(),
                step.getItemOrder(),
                step.getInteractionType());
        GroupModeRuntime runtime =
                runtimeRegistry.require(conversation.getMode());
        return executeStep(
                runtime, conversation, turn,
                new PreparedAction(action, step));
    }

    boolean isManualStep(
            GroupConversation conversation,
            GroupChatReplyStep step) {
        return actorRuntimeService.snapshot(conversation, step).manual();
    }

    Flux<GroupChatEvent> waitForManualInput(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step) {
        return Flux.defer(() -> {
            LocalDateTime now = LocalDateTime.now();
            step.setStatus(GroupChatConstant.STATUS_WAITING_INPUT)
                    .setUpdatedAt(now);
            turn.setStatus(GroupChatConstant.STATUS_WAITING_INPUT)
                    .setUpdatedAt(now);
            transactionTemplate.executeWithoutResult(status -> {
                stepMapper.updateById(step);
                turnMapper.updateById(turn);
            });
            String speakerName = runtimeRegistry
                    .require(conversation.getMode())
                    .agentPolicy()
                    .actorName(conversation.getUserWorldId(),
                            new GroupActorRef(
                                    step.getSpeakerType(),
                                    step.getSpeakerId()));
            return Flux.just(GroupChatEvent.builder()
                    .eventType(GroupChatConstant.EVENT_TURN_WAITING_INPUT)
                    .conversationId(conversation.getId())
                    .turnId(turn.getId())
                    .replyStepId(step.getId())
                    .actionType(step.getActionType())
                    .groupKey(step.getGroupKey())
                    .groupName(step.getGroupName())
                    .groupOrder(step.getGroupOrder())
                    .itemOrder(step.getItemOrder())
                    .speaker(GroupChatEvent.Speaker.builder()
                            .type(step.getSpeakerType())
                            .id(step.getSpeakerId())
                            .name(speakerName)
                            .build())
                    .build());
        });
    }

    boolean shouldSkipStep(GroupChatReplyStep scheduledStep) {
        GroupChatReplyStep persisted = stepMapper.selectById(scheduledStep.getId());
        return persisted != null
                && GroupChatConstant.STATUS_CANCELLED.equals(persisted.getStatus());
    }

    private GroupChatMessage createStreamingMessage(GroupConversation conversation, GroupChatTurn turn,
                                                    GroupChatReplyStep step) {
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setSceneId(sceneId(turn))
                .setTurnId(turn.getId())
                .setReplyStepId(step.getId())
                .setSpeakerType(step.getSpeakerType())
                .setSpeakerId(step.getSpeakerId())
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setVisibility("public")
                .setContent("")
                .setSequenceNo(conversationService.nextSequence(conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_STREAMING)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
        step.setOutputMessageId(message.getId());
        stepMapper.updateById(step);
        return message;
    }

    private Long sceneId(GroupChatTurn turn) {
        return GroupChatConstant.PLAN_SOURCE_SCENE.equals(turn.getPlanSource())
                ? turn.getPlanContextId() : null;
    }

    private List<GroupChatEvent> toEvents(ChatResponse response, GroupConversation conversation,
                                          GroupChatTurn turn, GroupChatReplyStep step,
                                          GroupChatMessage message, GroupChatEvent.Speaker speaker,
                                          GenerationAccumulator accumulator) {
        if (response == null || response.getResults() == null) {
            return List.of();
        }
        List<GroupChatEvent> events = new ArrayList<>();
        events.addAll(materialEvents(
                conversation, turn, step, accumulator));
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (isDirectTool(
                    generation, "publishExplorationScenes")) {
                if (accumulator.sceneOptions != null) {
                    throw new IllegalStateException(
                            "同一回复步骤不能多次公布选景地点");
                }
                TrpgSceneSelectionService.SceneOptionsResult result =
                        readSceneOptions(output.getText());
                accumulator.sceneOptions = result;
                accumulator.content.append(
                        canonicalOptionsMessage(result));
                if (result.autoAssigned()) {
                    recoveryService.cancelPendingSteps(
                            turn.getId(), "单地点已自动分配");
                }
                if (result.timeChanged()) {
                    events.add(baseEvent(
                            GroupChatConstant.EVENT_GAME_TIME_CHANGED,
                            conversation, turn, step, message, speaker)
                            .gameTime(result.gameTime())
                            .build());
                }
                events.add(baseEvent(
                        GroupChatConstant.EVENT_SCENE_OPTIONS_CREATED,
                        conversation, turn, step, message, speaker)
                        .sceneOptions(result.options())
                        .autoSelected(result.autoAssigned())
                        .build());
                continue;
            }
            if (isDirectDiceGeneration(generation)) {
                if (accumulator.diceRoll != null) {
                    throw new IllegalStateException("同一回复步骤不能返回多个直接掷骰结果");
                }
                accumulator.diceRoll = readDirectDiceResult(output.getText());
                events.add(baseEvent(GroupChatConstant.EVENT_DICE_ROLL_CREATED,
                        conversation, turn, step, message, speaker)
                        .toolName(generation.getMetadata().get(
                                ToolExecutionResult.METADATA_TOOL_NAME))
                        .diceRoll(accumulator.diceRoll)
                        .build());
                continue;
            }
            if (isDirectInteraction(generation)) {
                if (accumulator.interaction != null) {
                    throw new IllegalStateException(
                            "同一回复步骤不能发起多个追问");
                }
                accumulator.interaction = readInteractionRequest(
                        output.getText());
                accumulator.content.append(
                        accumulator.interaction.question());
                events.add(baseEvent(
                        GroupChatConstant.EVENT_MESSAGE_DELTA,
                        conversation, turn, step, message, speaker)
                        .delta(accumulator.interaction.question())
                        .build());
                continue;
            }
            Object reasoningValue = output.getMetadata()
                    .get("reasoningContent");
            if (reasoningValue instanceof String reasoning
                    && StringUtils.hasText(reasoning)) {
                accumulator.reasoning.append(reasoning);
                events.add(baseEvent(GroupChatConstant.EVENT_REASONING_DELTA,
                        conversation, turn, step, message, speaker)
                        .delta(reasoning)
                        .build());
            }
            String content = output.getText();
            if (StringUtils.hasText(content)) {
                if (accumulator.decisionActionParser == null) {
                    accumulator.content.append(content);
                    events.add(baseEvent(
                            GroupChatConstant.EVENT_MESSAGE_DELTA,
                            conversation, turn, step, message, speaker)
                            .delta(content).build());
                } else {
                    DecisionActionStreamParser.Delta delta =
                            accumulator.decisionActionParser.accept(content);
                    if (!delta.decision().isEmpty()) {
                        events.add(baseEvent(
                                GroupChatConstant.EVENT_DECISION_DELTA,
                                conversation, turn, step, message,
                                speaker).delta(delta.decision()).build());
                    }
                    if (delta.decisionCompleted()) {
                        String decision =
                                accumulator.decisionActionParser
                                        .decision();
                        decisionStore.save(step.getId(), decision);
                        events.add(baseEvent(
                                GroupChatConstant
                                        .EVENT_DECISION_COMPLETED,
                                conversation, turn, step, message,
                                speaker).content(decision).build());
                    }
                    if (!delta.action().isEmpty()) {
                        accumulator.content.append(delta.action());
                        events.add(baseEvent(
                                GroupChatConstant.EVENT_MESSAGE_DELTA,
                                conversation, turn, step, message,
                                speaker).delta(delta.action()).build());
                    }
                }
            }
        }
        return events;
    }

    private List<GroupChatEvent> materialEvents(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step,
            GenerationAccumulator accumulator) {
        List<GroupChatMessage> materials = materialMessageFeed.listNew(
                step.getId(), accumulator.lastMaterialMessageId);
        if (materials.isEmpty()) {
            return List.of();
        }
        List<GroupChatEvent> events = new ArrayList<>();
        for (GroupChatMessage material : materials) {
            accumulator.lastMaterialMessageId = material.getId();
            GroupChatEvent.Speaker materialSpeaker =
                    GroupChatEvent.Speaker.builder()
                            .type(GroupChatConstant.ACTOR_KP)
                            .name("KP")
                            .build();
            events.add(baseEvent(
                    GroupChatConstant.EVENT_MATERIAL_CREATED,
                    conversation, turn, step, material,
                    materialSpeaker)
                    .content(material.getContent())
                    .build());
        }
        return events;
    }

    private boolean isDirectDiceGeneration(Generation generation) {
        if (generation == null || generation.getMetadata() == null) {
            return false;
        }
        String toolName = generation.getMetadata().get(
                ToolExecutionResult.METADATA_TOOL_NAME);
        return toolName != null
                && DiceRollConstant.KP_STATE_TOOL_NAMES.contains(toolName)
                && isDirectTool(generation, toolName);
    }

    private boolean usesDecisionActionProtocol(
            GroupConversation conversation, GroupActionSpec action) {
        return GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())
                && GroupChatConstant.ACTOR_CHARACTER.equals(
                action.actorType())
                && (GroupChatConstant.ACTION_TRPG_SCENE.equals(
                action.actionType())
                || GroupChatConstant.ACTION_TRPG_COMBAT.equals(
                action.actionType())
                || GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                action.actionType())
                || GroupChatConstant.ACTION_COMBAT_DEFENSE.equals(
                action.actionType())
                || GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE.equals(
                action.actionType()));
    }

    private boolean isDirectTool(
            Generation generation, String toolName) {
        if (generation == null || generation.getMetadata() == null
                || !ToolExecutionResult.FINISH_REASON.equals(
                generation.getMetadata().getFinishReason())) {
            return false;
        }
        return toolName.equals(generation.getMetadata().get(
                ToolExecutionResult.METADATA_TOOL_NAME));
    }

    private boolean isDirectInteraction(Generation generation) {
        return isDirectTool(generation, "askForClarification")
                || isDirectTool(generation, "askKp");
    }

    private TrpgSceneSelectionService.SceneOptionsResult
            readSceneOptions(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException(
                    "选景工具未返回结构化结果");
        }
        try {
            return objectMapper.readValue(
                    content,
                    TrpgSceneSelectionService.SceneOptionsResult.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "选景工具返回结果无法解析", exception);
        }
    }

    private TrpgStepInteractionService.InteractionRequest
            readInteractionRequest(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException(
                    "追问工具未返回结构化结果");
        }
        try {
            TrpgStepInteractionService.InteractionRequest result =
                    objectMapper.readValue(content,
                            TrpgStepInteractionService
                                    .InteractionRequest.class);
            if (result == null || result.childStepId() == null
                    || !StringUtils.hasText(result.question())) {
                throw new IllegalStateException(
                        "追问工具结果缺少子步骤或问题");
            }
            return result;
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "追问工具结果无法解析", exception);
        }
    }

    private String canonicalOptionsMessage(
            TrpgSceneSelectionService.SceneOptionsResult result) {
        StringBuilder content =
                new StringBuilder("KP公布可探索地点：");
        result.options().forEach((number, name) ->
                content.append("\n")
                        .append(number).append(":").append(name));
        if (result.autoAssigned()) {
            content.append(
                    "\n仅有一个地点，所有调查员已自动进入该场景。");
        }
        return content.toString();
    }

    private KpDiceToolResult readDirectDiceResult(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("直接掷骰工具未返回结构化结果");
        }
        try {
            KpDiceToolResult result =
                    objectMapper.readValue(content, KpDiceToolResult.class);
            if (result == null || result.summary() == null
                    || result.summary().getId() == null) {
                throw new IllegalStateException("直接掷骰工具结果缺少概要id");
            }
            return result;
        } catch (JacksonException exception) {
            throw new IllegalStateException("直接掷骰工具返回结果无法解析", exception);
        }
    }

    private void finalizeCompletedStep(
                                       GroupConversation conversation,
                                       GroupChatTurn turn,
                                       GroupChatReplyStep step, GroupChatMessage message,
                                       GenerationAccumulator accumulator, FinalizationGuard guard) {
        guard.run(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    persistOutput(message, accumulator,
                            GroupChatConstant.STATUS_COMPLETED);
                    if (accumulator.interaction != null) {
                        LocalDateTime now = LocalDateTime.now();
                        step.setStatus(GroupChatConstant
                                        .STATUS_WAITING_INTERACTION)
                                .setErrorMessage(null)
                                .setUpdatedAt(now);
                        stepMapper.update(null,
                                new LambdaUpdateWrapper<
                                        GroupChatReplyStep>()
                                        .eq(GroupChatReplyStep::getId,
                                                step.getId())
                                        .set(GroupChatReplyStep::getStatus,
                                                GroupChatConstant
                                                        .STATUS_WAITING_INTERACTION)
                                        .set(GroupChatReplyStep
                                                        ::getOutputMessageId,
                                                message.getId())
                                        .set(GroupChatReplyStep
                                                        ::getErrorMessage,
                                                null)
                                        .set(GroupChatReplyStep::getUpdatedAt,
                                                now));
                        stepMapper.update(null,
                                new LambdaUpdateWrapper<
                                        GroupChatReplyStep>()
                                        .eq(GroupChatReplyStep::getId,
                                                accumulator.interaction
                                                        .childStepId())
                                        .set(GroupChatReplyStep
                                                        ::getPromptMessageId,
                                                message.getId())
                                        .set(GroupChatReplyStep::getUpdatedAt,
                                                now));
                        return;
                    }
                    if (accumulator.diceRoll != null
                            && GroupChatConstant.MODE_TRPG.equals(
                            conversation.getMode())) {
                        String pausedStatus = DiceRollConstant.STATUS_PENDING
                                .equals(accumulator.diceRoll.summary()
                                        .getStatus())
                                ? GroupChatConstant.STATUS_WAITING_DICE
                                : GroupChatConstant.STATUS_PAUSED;
                        step.setOutputMessageId(null);
                        step.setStatus(pausedStatus)
                                .setErrorMessage(null)
                                .setUpdatedAt(LocalDateTime.now());
                        stepMapper.update(
                                null,
                                new LambdaUpdateWrapper<
                                        GroupChatReplyStep>()
                                        .eq(GroupChatReplyStep::getId,
                                                step.getId())
                                        .set(GroupChatReplyStep
                                                        ::getOutputMessageId,
                                                null)
                                        .set(GroupChatReplyStep::getStatus,
                                                pausedStatus)
                                        .set(GroupChatReplyStep
                                                        ::getErrorMessage,
                                                null)
                                        .set(GroupChatReplyStep::getUpdatedAt,
                                                step.getUpdatedAt()));
                        turn.setStatus(pausedStatus)
                                .setUpdatedAt(LocalDateTime.now());
                        turnMapper.updateById(turn);
                        checkpointService.recordBoundary(
                                turn, step,
                                GroupChatConstant.STATUS_WAITING_DICE
                                        .equals(pausedStatus)
                                        ? GroupTurnCheckpointService
                                                .WAITING_DICE
                                        : GroupTurnCheckpointService
                                                .PAUSED);
                        return;
                    }
                    updateStepStatus(step,
                            GroupChatConstant.STATUS_COMPLETED, null);
                    if (GroupChatConstant
                            .ACTION_COMBAT_ADJUDICATE.equals(
                            step.getActionType())) {
                        boolean finished =
                                combatLifecycleService
                                        .completeAdjudication(
                                conversation, turn, step, message);
                        if (finished) {
                            recoveryService.cancelPendingSteps(
                                    turn.getId(), "战斗已结束");
                            turn.setStatus(
                                            GroupChatConstant
                                                    .STATUS_COMPLETED)
                                    .setUpdatedAt(
                                            LocalDateTime.now());
                            turnMapper.updateById(turn);
                        }
                    }
                    if (GroupChatConstant.MODE_TRPG.equals(
                            conversation.getMode())) {
                        checkpointService.recordBoundary(
                                turn, step,
                                GroupTurnCheckpointService.COMPLETED);
                    }
                }));
    }

    private void finalizeFailedStep(GroupChatTurn turn, GroupChatReplyStep step, GroupChatMessage message,
                                    GenerationAccumulator accumulator, FinalizationGuard guard, Throwable error) {
        guard.run(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    if (message != null) {
                        persistOutput(message, accumulator, GroupChatConstant.STATUS_FAILED);
                    }
                    String reason = errorMessage(error);
                    updateStepStatus(step, GroupChatConstant.STATUS_FAILED, reason);
                    if (accumulator.decisionActionParser != null
                            || !GroupChatConstant.PLAN_SOURCE_USER.equals(
                            turn.getPlanSource())) {
                        recoveryService.blockPendingSteps(
                                turn.getId(), reason);
                    } else {
                        recoveryService.cancelPendingSteps(
                                turn.getId(), reason);
                    }
                }));
    }

    private void finalizeCancelledStep(GroupChatTurn turn, GroupChatReplyStep step, GroupChatMessage message,
                                       GenerationAccumulator accumulator, FinalizationGuard guard) {
        guard.run(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    if (message != null) {
                        persistOutput(message, accumulator, GroupChatConstant.STATUS_CANCELLED);
                    }
                    updateStepStatus(step, GroupChatConstant.STATUS_CANCELLED, "客户端取消生成");
                    recoveryService.cancelPendingSteps(turn.getId(), "客户端取消生成");
                }));
    }

    private void persistOutput(GroupChatMessage message, GenerationAccumulator accumulator, String status) {
        if (accumulator.diceRoll != null) {
            List<Integer> roundNos = accumulator.diceRoll.results().stream()
                    .map(DiceRollDetailVO::getRoundNo)
                    .toList();
            message.setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                    .setContent(diceMessageCodec.encode(
                            accumulator.diceRoll.summary().getId(), roundNos));
        } else {
            message.setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                    .setContent(accumulator.content.toString());
        }
        message
                .setStatus(status)
                .setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(message);
    }

    private void updateStepStatus(GroupChatReplyStep step, String status, String error) {
        step.setStatus(status).setErrorMessage(error).setUpdatedAt(LocalDateTime.now());
        stepMapper.update(null,
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getId, step.getId())
                        .set(GroupChatReplyStep::getStatus, status)
                        .set(GroupChatReplyStep::getErrorMessage, error)
                        .set(GroupChatReplyStep::getUpdatedAt,
                                step.getUpdatedAt()));
    }

    void completeTurn(GroupChatTurn turn) {
        transitionTurn(turn, GroupChatConstant.STATUS_COMPLETED);
    }

    void cancelTurn(GroupChatTurn turn) {
        transitionTurn(turn, GroupChatConstant.STATUS_CANCELLED);
    }

    private void transitionTurn(GroupChatTurn turn, String targetStatus) {
        if (turn == null || turn.getId() == null || isTerminal(turn.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = turnMapper.update(
                new GroupChatTurn().setStatus(targetStatus).setUpdatedAt(now),
                new LambdaUpdateWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getId, turn.getId())
                        .in(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_PENDING,
                                GroupChatConstant.STATUS_RUNNING));
        if (updated > 0) {
            turn.setStatus(targetStatus).setUpdatedAt(now);
        }
    }

    private boolean isTerminal(String status) {
        return !GroupChatConstant.STATUS_PENDING.equals(status)
                && !GroupChatConstant.STATUS_RUNNING.equals(status);
    }

    private GroupChatEvent failureEvent(Long conversationId, Long turnId, Throwable error) {
        return GroupChatEvent.builder()
                .eventType(GroupChatConstant.EVENT_REPLY_FAILED)
                .conversationId(conversationId)
                .turnId(turnId)
                .error(errorMessage(error))
                .build();
    }

    private String errorMessage(Throwable error) {
        return error == null || !StringUtils.hasText(error.getMessage())
                ? "生成失败" : error.getMessage();
    }

    private GroupChatEvent.GroupChatEventBuilder baseEvent(String eventType, GroupConversation conversation,
                                                           GroupChatTurn turn, GroupChatReplyStep step,
                                                           GroupChatMessage message,
                                                           GroupChatEvent.Speaker speaker) {
        return GroupChatEvent.builder()
                .eventType(eventType)
                .conversationId(conversation.getId())
                .turnId(turn.getId())
                .replyStepId(step.getId())
                .actionType(step.getActionType())
                .groupKey(step.getGroupKey())
                .groupName(step.getGroupName())
                .groupOrder(step.getGroupOrder())
                .itemOrder(step.getItemOrder())
                .messageId(message.getId())
                .sequence(message.getSequenceNo())
                .messageKind(message.getMessageKind())
                .speaker(speaker);
    }

    private String speakerName(GroupConversation conversation, GroupChatMessage message) {
        if (GroupChatConstant.ACTOR_USER.equals(message.getSpeakerType())) {
            return "用户";
        }
        if (GroupChatConstant.ACTOR_CHARACTER.equals(message.getSpeakerType())
                || GroupChatConstant.ACTOR_KP.equals(message.getSpeakerType())) {
            return runtimeRegistry.require(conversation.getMode()).agentPolicy()
                    .actorName(conversation.getUserWorldId(),
                            new GroupActorRef(message.getSpeakerType(), message.getSpeakerId()));
        }
        return "旁白";
    }

    private void validateRequest(GroupChatRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.getContent())) {
            throw new UserRequestException("群聊消息内容不能为空");
        }
        if (request.getClientRequestId() != null && request.getClientRequestId().length() > 100) {
            throw new UserRequestException("clientRequestId长度不能超过100");
        }
    }

    private record PreparedTurn(GroupChatTurn turn, GroupChatMessage userMessage,
                                List<PreparedAction> actions) {
    }

    private record PreparedAction(GroupActionSpec action, GroupChatReplyStep step) {
    }

    private record BufferedSelectionOutput(
            TrpgSceneSelectionService.SceneChoiceResult directResult,
            String pureNumber) {
    }

    private static class GenerationAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final DecisionActionStreamParser decisionActionParser;
        private KpDiceToolResult diceRoll;
        private TrpgStepInteractionService.InteractionRequest interaction;
        private TrpgSceneSelectionService.SceneOptionsResult sceneOptions;
        private Long lastMaterialMessageId;

        private GenerationAccumulator(
                boolean decisionActionProtocol) {
            this.decisionActionParser = decisionActionProtocol
                    ? new DecisionActionStreamParser() : null;
        }

        private void finishDecisionAction() {
            if (decisionActionParser != null) {
                decisionActionParser.finish();
            }
        }

        private void assertValidOutput() {
            if (StringUtils.hasText(content)
                    || diceRoll != null
                    || interaction != null
                    || sceneOptions != null
                    || lastMaterialMessageId != null) {
                return;
            }
            throw new IllegalStateException("模型未返回有效内容");
        }
    }

    static final class FinalizationGuard {
        private boolean finalized;

        synchronized void run(Runnable action) {
            if (finalized) {
                return;
            }
            action.run();
            finalized = true;
        }
    }
}
