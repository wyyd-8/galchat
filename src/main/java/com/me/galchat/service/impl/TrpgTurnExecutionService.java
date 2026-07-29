package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupSceneSelectionDTO;
import com.me.galchat.domain.dto.GroupTurnStartDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.GroupCurrentTurnVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TrpgTurnExecutionService {

    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupTurnPlanResolver planResolver;
    private final GroupRuntimeRegistry runtimeRegistry;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupTurnRecoveryService recoveryService;
    private final GroupChatService groupChatService;
    private final TransactionTemplate transactionTemplate;
    @Autowired
    private TrpgSceneSelectionService sceneSelectionService;
    @Autowired
    private TrpgSceneLifecycleService sceneLifecycleService;
    @Autowired
    private TrpgSceneSelectionStore sceneSelectionStore;
    @Autowired
    private TrpgParticipantService participantService;

    public TrpgTurnExecutionService(
            GroupConversationService conversationService,
            GroupConversationLockService lockService,
            GroupTurnPlanResolver planResolver,
            GroupRuntimeRegistry runtimeRegistry,
            GroupChatTurnMapper turnMapper,
            GroupChatReplyStepMapper stepMapper,
            GroupChatMessageMapper messageMapper,
            GroupTurnRecoveryService recoveryService,
            GroupChatService groupChatService,
            TransactionTemplate transactionTemplate) {
        this.conversationService = conversationService;
        this.lockService = lockService;
        this.planResolver = planResolver;
        this.runtimeRegistry = runtimeRegistry;
        this.turnMapper = turnMapper;
        this.stepMapper = stepMapper;
        this.messageMapper = messageMapper;
        this.recoveryService = recoveryService;
        this.groupChatService = groupChatService;
        this.transactionTemplate = transactionTemplate;
    }

    public Flux<GroupChatEvent> start(
            Long conversationId, GroupTurnStartDTO request) {
        return Flux.defer(() -> {
            validateStartRequest(request);
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                GroupConversation conversation =
                        conversationService.requireActive(conversationId);
                requireTrpg(conversation);
                WaitingInput waiting =
                        findWaitingInput(conversationId);
                if (waiting != null) {
                    return Flux.just(waitingEvent(
                                    conversation, waiting.turn(),
                                    waiting.step()))
                            .doFinally(signal ->
                                    lockService.unlock(lock));
                }
                recoveryService.recoverInterrupted(conversationId);
                PreparedTurn prepared = transactionTemplate.execute(
                        status -> createTurn(conversation, request));
                if (prepared == null) {
                    throw new UserRequestException("创建行动轮失败");
                }
                Flux<GroupChatEvent> accepted = Flux.just(
                        GroupChatEvent.builder()
                                .eventType(
                                        GroupChatConstant.EVENT_TURN_ACCEPTED)
                                .conversationId(conversationId)
                                .turnId(prepared.turn().getId())
                                .build());
                int userIndex = firstUserStep(prepared.steps());
                List<GroupChatReplyStep> executable = userIndex < 0
                        ? prepared.steps()
                        : prepared.steps().subList(0, userIndex);
                Flux<GroupChatEvent> replies =
                        Flux.fromIterable(executable)
                                .concatMap(step ->
                                        groupChatService.streamPersistedStep(
                                                conversation,
                                                prepared.turn(), step));
                Flux<GroupChatEvent> tail = userIndex < 0
                        ? complete(conversation, prepared.turn())
                        : continueFromUserBoundary(
                                conversation, prepared.turn(),
                                prepared.steps().get(userIndex));
                return Flux.concat(accepted, replies, tail)
                        .doOnError(error ->
                                recoveryService.recoverInterrupted(
                                        conversationId))
                        .doFinally(signal -> lockService.unlock(lock));
            } catch (RuntimeException exception) {
                lockService.unlock(lock);
                return Flux.error(exception);
            }
        });
    }

    public GroupCurrentTurnVO current(Long conversationId) {
        GroupConversation conversation =
                conversationService.requireAuthorized(conversationId);
        requireTrpg(conversation);
        List<GroupChatTurn> turns = turnMapper.selectList(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId,
                                conversationId)
                        .in(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_RUNNING,
                                GroupChatConstant.STATUS_WAITING_INPUT)
                        .orderByDesc(GroupChatTurn::getId)
                        .last("limit 1"));
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        GroupChatTurn turn = turns.getFirst();
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .in(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_RUNNING,
                                GroupChatConstant.STATUS_WAITING_INPUT)
                        .orderByAsc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        GroupChatReplyStep step = steps == null || steps.isEmpty()
                ? null : steps.getFirst();
        boolean waiting = step != null
                && GroupChatConstant.STATUS_WAITING_INPUT.equals(
                        step.getStatus())
                && GroupChatConstant.ACTOR_USER.equals(
                        step.getSpeakerType());
        String inputType = step == null ? null
                : GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                        step.getActionType())
                ? "selection" : "message";
        Map<String, String> options = Map.of();
        if ("selection".equals(inputType)
                && sceneSelectionStore != null) {
            Map<String, String> names = new java.util.LinkedHashMap<>();
            sceneSelectionStore.getOptions(
                            conversationId, turn.getId())
                    .forEach((number, option) ->
                            names.put(number, option.name()));
            options = java.util.Collections.unmodifiableMap(names);
        }
        return new GroupCurrentTurnVO(
                turn.getId(), turn.getPlanId(),
                turn.getPlanSource(), turn.getPlanContextId(),
                turn.getStatus(),
                step == null ? null : step.getId(),
                step == null ? null : step.getActionType(),
                inputType,
                step == null ? null : step.getGroupName(),
                waiting,
                options);
    }

    public Flux<GroupChatEvent> submitMessage(
            Long conversationId,
            Long turnId,
            Long stepId,
            GroupChatRequestDTO request) {
        return Flux.defer(() -> {
            validateMessageRequest(request);
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                GroupConversation conversation =
                        conversationService.requireActive(conversationId);
                requireTrpg(conversation);
                GroupChatTurn turn =
                        requireWaitingTurn(conversationId, turnId);
                GroupChatReplyStep userStep =
                        requireWaitingUserStep(turnId, stepId);
                GroupChatMessage message = transactionTemplate.execute(
                        status -> completeUserStep(
                                conversation, turn, userStep, request));
                if (message == null) {
                    throw new UserRequestException("保存用户行动失败");
                }
                return continueAfterUserAction(
                        conversation, turn, userStep, message, lock);
            } catch (RuntimeException exception) {
                lockService.unlock(lock);
                return Flux.error(exception);
            }
        });
    }

    public Flux<GroupChatEvent> submitSelection(
            Long conversationId,
            Long turnId,
            Long stepId,
            GroupSceneSelectionDTO request) {
        return Flux.defer(() -> {
            if (request == null) {
                throw new UserRequestException("选景请求不能为空");
            }
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                GroupConversation conversation =
                        conversationService.requireActive(conversationId);
                requireTrpg(conversation);
                GroupChatTurn turn =
                        requireWaitingTurn(conversationId, turnId);
                GroupChatReplyStep userStep =
                        requireWaitingUserStep(turnId, stepId);
                if (!GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                        userStep.getActionType())) {
                    throw new UserRequestException(
                            "当前用户步骤不是选景步骤");
                }
                TrpgSceneSelectionService.SceneChoiceResult choice =
                        sceneSelectionService.selectOption(
                                conversationId, turnId,
                                new GroupActorRef(
                                        GroupChatConstant.ACTOR_USER,
                                        userStep.getSpeakerId()),
                                request.getOptionNo());
                GroupChatMessage message = transactionTemplate.execute(
                        status -> completeStructuredUserStep(
                                conversation, turn, userStep,
                                "用户:" + choice.investigatorName()
                                        + ":" + choice.locationName()));
                if (message == null) {
                    throw new UserRequestException("保存用户选景失败");
                }
                return continueAfterUserAction(
                        conversation, turn, userStep, message, lock);
            } catch (RuntimeException exception) {
                lockService.unlock(lock);
                return Flux.error(exception);
            }
        });
    }

    public Flux<GroupChatEvent> endExploration(
            Long conversationId,
            Long turnId,
            Long stepId,
            GroupTurnStartDTO request) {
        return Flux.defer(() -> {
            validateStartRequest(request);
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                GroupConversation conversation =
                        conversationService.requireActive(conversationId);
                requireTrpg(conversation);
                GroupChatTurn turn =
                        requireWaitingTurn(conversationId, turnId);
                GroupChatReplyStep userStep =
                        requireWaitingUserStep(turnId, stepId);
                if (!GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                        turn.getPlanSource())
                        || !GroupChatConstant.ACTION_TRPG_SCENE.equals(
                                userStep.getActionType())) {
                    throw new UserRequestException(
                            "当前用户步骤不属于场景探索");
                }
                sceneLifecycleService.requestInvestigatorFinish(
                        conversationId, stepId,
                        new GroupActorRef(
                                GroupChatConstant.ACTOR_USER,
                                userStep.getSpeakerId()));
                GroupChatMessage message = transactionTemplate.execute(
                        status -> completeStructuredUserStep(
                                conversation, turn, userStep,
                                "用户调查员已结束当前场景探索。"));
                if (message == null) {
                    throw new UserRequestException(
                            "保存结束探索操作失败");
                }
                return continueAfterUserAction(
                        conversation, turn, userStep, message, lock);
            } catch (RuntimeException exception) {
                lockService.unlock(lock);
                return Flux.error(exception);
            }
        });
    }

    private Flux<GroupChatEvent> continueAfterUserAction(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep userStep,
            GroupChatMessage message,
            GroupConversationLockService.OwnedLock lock) {
        List<GroupChatReplyStep> remaining =
                stepMapper.selectList(
                        new LambdaQueryWrapper<GroupChatReplyStep>()
                                .eq(GroupChatReplyStep::getTurnId,
                                        turn.getId())
                                .gt(GroupChatReplyStep::getStepNo,
                                        userStep.getStepNo())
                                .eq(GroupChatReplyStep::getStatus,
                                        GroupChatConstant.STATUS_PENDING)
                                .orderByAsc(
                                        GroupChatReplyStep::getStepNo));
        Flux<GroupChatEvent> accepted = Flux.just(
                GroupChatEvent.builder()
                        .eventType(GroupChatConstant.EVENT_TURN_ACCEPTED)
                        .conversationId(conversation.getId())
                        .turnId(turn.getId())
                        .replyStepId(userStep.getId())
                        .messageId(message.getId())
                        .sequence(message.getSequenceNo())
                        .build());
        int nextUserIndex = firstUserStep(remaining);
        List<GroupChatReplyStep> executable =
                nextUserIndex < 0
                        ? remaining
                        : remaining.subList(0, nextUserIndex);
        Flux<GroupChatEvent> replies =
                Flux.fromIterable(executable)
                        .concatMap(step ->
                                groupChatService.streamPersistedStep(
                                        conversation, turn, step));
        Flux<GroupChatEvent> tail = nextUserIndex < 0
                ? complete(conversation, turn)
                : waitForUser(
                        conversation, turn,
                        remaining.get(nextUserIndex));
        return Flux.concat(accepted, replies, tail)
                .doOnError(error ->
                        recoveryService.recoverInterrupted(
                                conversation.getId()))
                .doFinally(signal -> lockService.unlock(lock));
    }

    private GroupChatMessage completeUserStep(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep userStep,
            GroupChatRequestDTO request) {
        return completeStructuredUserStep(
                conversation, turn, userStep,
                request.getContent().trim());
    }

    private GroupChatMessage completeStructuredUserStep(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep userStep,
            String content) {
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setSceneId(GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                        turn.getPlanSource())
                        ? turn.getPlanContextId() : null)
                .setTurnId(turn.getId())
                .setReplyStepId(userStep.getId())
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(userStep.getSpeakerId())
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setVisibility("public")
                .setContent(content)
                .setSequenceNo(conversationService.nextSequence(
                        conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
        userStep.setOutputMessageId(message.getId())
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setUpdatedAt(now);
        stepMapper.updateById(userStep);
        turn.setStatus(GroupChatConstant.STATUS_RUNNING)
                .setUpdatedAt(now);
        turnMapper.updateById(turn);
        return message;
    }

    private GroupChatTurn requireWaitingTurn(
            Long conversationId, Long turnId) {
        GroupChatTurn turn = turnMapper.selectById(turnId);
        if (turn == null
                || !conversationId.equals(turn.getConversationId())) {
            throw new UserRequestException("行动轮不存在");
        }
        if (!GroupChatConstant.STATUS_WAITING_INPUT.equals(
                turn.getStatus())) {
            throw new UserRequestException("行动轮当前不等待用户输入");
        }
        return turn;
    }

    private GroupChatReplyStep requireWaitingUserStep(
            Long turnId, Long stepId) {
        GroupChatReplyStep step = stepMapper.selectById(stepId);
        if (step == null || !turnId.equals(step.getTurnId())) {
            throw new UserRequestException("用户行动步骤不存在");
        }
        if (!GroupChatConstant.ACTOR_USER.equals(step.getSpeakerType())
                || !GroupChatConstant.STATUS_WAITING_INPUT.equals(
                        step.getStatus())) {
            throw new UserRequestException("当前步骤不等待用户输入");
        }
        return step;
    }

    private PreparedTurn createTurn(
            GroupConversation conversation, GroupTurnStartDTO request) {
        if (StringUtils.hasText(request.getClientRequestId())) {
            Long duplicateCount = turnMapper.selectCount(
                    new LambdaQueryWrapper<GroupChatTurn>()
                            .eq(GroupChatTurn::getConversationId,
                                    conversation.getId())
                            .eq(GroupChatTurn::getClientRequestId,
                                    request.getClientRequestId().trim()));
            if (duplicateCount != null && duplicateCount > 0) {
                throw new UserRequestException(
                        "clientRequestId已处理，请勿重复提交");
            }
        }
        GroupModeRuntime runtime =
                runtimeRegistry.require(conversation.getMode());
        List<TrpgParticipantService.Participant> participants =
                participantService == null
                        ? List.of()
                        : participantService.listInvestigators(
                                conversation);
        GroupTurnPlanResolver.ResolvedTurnPlan resolved =
                planResolver.resolve(conversation, runtime);
        List<GroupActionSpec> actions =
                withSceneIntro(conversation, resolved);
        int syntheticSteps = actions.stream().anyMatch(action ->
                GroupChatConstant.ACTION_TRPG_SCENE_INTRO.equals(
                        action.actionType())) ? 1 : 0;
        if (actions.size()
                > GroupChatConstant.MAX_REPLY_STEPS
                + syntheticSteps) {
            throw new UserRequestException(
                    "单次行动轮人物数量不能超过"
                            + GroupChatConstant.MAX_REPLY_STEPS);
        }
        LocalDateTime now = LocalDateTime.now();
        GroupChatTurn turn = new GroupChatTurn()
                .setConversationId(conversation.getId())
                .setClientRequestId(
                        StringUtils.hasText(request.getClientRequestId())
                                ? request.getClientRequestId().trim()
                                : null)
                .setPlanId(conversation.getActiveReplyPlanId())
                .setPlanSource(resolved.source())
                .setPlanContextId(resolved.contextId())
                .setStatus(GroupChatConstant.STATUS_RUNNING)
                .setRevision(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        turnMapper.insert(turn);
        List<GroupChatReplyStep> steps = new ArrayList<>();
        for (int index = 0; index < actions.size();
             index++) {
            GroupActionSpec action = actions.get(index);
            if (!GroupChatConstant.ACTOR_USER.equals(
                    action.actorType())) {
                conversationService.checkReplyMember(
                        conversation.getId(),
                        action.actorType(), action.actorId(), false);
            } else if (!participants.isEmpty()
                    && participants.stream().noneMatch(participant ->
                    participant.actor().equals(action.actor()))) {
                throw new UserRequestException(
                        "回复计划中的用户调查员与当前人物卡不一致");
            }
            GroupChatReplyStep step = new GroupChatReplyStep()
                    .setTurnId(turn.getId())
                    .setGroupKey(action.groupKey())
                    .setGroupName(action.groupName())
                    .setGroupOrder(action.groupOrder())
                    .setItemOrder(action.itemOrder())
                    .setStepNo(index + 1)
                    .setActionType(action.actionType())
                    .setSpeakerType(action.actorType())
                    .setSpeakerId(action.actorId())
                    .setForceReply(false)
                    .setStatus(GroupChatConstant.STATUS_PENDING)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            stepMapper.insert(step);
            steps.add(step);
        }
        return new PreparedTurn(turn, List.copyOf(steps));
    }

    private List<GroupActionSpec> withSceneIntro(
            GroupConversation conversation,
            GroupTurnPlanResolver.ResolvedTurnPlan resolved) {
        if (!GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                resolved.source())
                || conversation.getActiveReplyPlanId() == null) {
            return resolved.actions();
        }
        Long completed = stepMapper.countCompletedActionByPlanId(
                conversation.getActiveReplyPlanId(),
                GroupChatConstant.ACTION_TRPG_SCENE_INTRO);
        if (completed != null && completed > 0) {
            return resolved.actions();
        }
        GroupActionSpec first = resolved.actions().isEmpty()
                ? null : resolved.actions().getFirst();
        GroupActionSpec intro = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE_INTRO,
                GroupChatConstant.ACTOR_KP,
                null,
                first == null ? "scene:" + resolved.contextId()
                        : first.groupKey(),
                first == null ? "场景引入" : first.groupName(),
                first == null ? 1 : first.groupOrder(),
                0);
        List<GroupActionSpec> actions = new ArrayList<>();
        actions.add(intro);
        actions.addAll(resolved.actions());
        return List.copyOf(actions);
    }

    private Flux<GroupChatEvent> waitForUser(
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
            return Flux.just(waitingEvent(conversation, turn, step));
        });
    }

    private Flux<GroupChatEvent> continueFromUserBoundary(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep scheduledUserStep) {
        return Flux.defer(() -> {
            GroupChatReplyStep persisted =
                    stepMapper.selectById(scheduledUserStep.getId());
            if (persisted == null
                    || GroupChatConstant.STATUS_PENDING.equals(
                            persisted.getStatus())) {
                if (GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                        scheduledUserStep.getActionType())
                        && sceneSelectionStore != null
                        && sceneSelectionStore.getOptions(
                                conversation.getId(), turn.getId())
                                .isEmpty()) {
                    throw new UserRequestException(
                            "KP未通过选景工具公布可选地点");
                }
                return waitForUser(
                        conversation, turn, scheduledUserStep);
            }
            if (GroupChatConstant.STATUS_WAITING_INPUT.equals(
                    persisted.getStatus())) {
                return Flux.just(waitingEvent(
                        conversation, turn, persisted));
            }
            List<GroupChatReplyStep> remaining =
                    stepMapper.selectList(
                            new LambdaQueryWrapper<GroupChatReplyStep>()
                                    .eq(GroupChatReplyStep::getTurnId,
                                            turn.getId())
                                    .gt(GroupChatReplyStep::getStepNo,
                                            scheduledUserStep.getStepNo())
                                    .eq(GroupChatReplyStep::getStatus,
                                            GroupChatConstant.STATUS_PENDING)
                                    .orderByAsc(
                                            GroupChatReplyStep::getStepNo));
            int nextUser = firstUserStep(remaining);
            List<GroupChatReplyStep> executable = nextUser < 0
                    ? remaining : remaining.subList(0, nextUser);
            Flux<GroupChatEvent> replies =
                    Flux.fromIterable(executable)
                            .concatMap(step ->
                                    groupChatService.streamPersistedStep(
                                            conversation, turn, step));
            Flux<GroupChatEvent> tail = nextUser < 0
                    ? complete(conversation, turn)
                    : continueFromUserBoundary(
                            conversation, turn,
                            remaining.get(nextUser));
            return Flux.concat(replies, tail);
        });
    }

    private WaitingInput findWaitingInput(Long conversationId) {
        List<GroupChatTurn> turns = turnMapper.selectList(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId,
                                conversationId)
                        .eq(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_WAITING_INPUT)
                        .orderByDesc(GroupChatTurn::getId)
                        .last("limit 1"));
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        GroupChatTurn turn = turns.getFirst();
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .eq(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_WAITING_INPUT)
                        .orderByAsc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        if (steps == null || steps.isEmpty()) {
            throw new UserRequestException(
                    "行动轮等待状态异常，请联系管理员");
        }
        GroupChatReplyStep step = steps.getFirst();
        if (GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                step.getActionType())
                && sceneSelectionStore != null
                && sceneSelectionStore.getOptions(
                        conversationId, turn.getId()).isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            step.setStatus(GroupChatConstant.STATUS_FAILED)
                    .setErrorMessage("选景等待状态已过期")
                    .setUpdatedAt(now);
            turn.setStatus(GroupChatConstant.STATUS_FAILED)
                    .setUpdatedAt(now);
            transactionTemplate.executeWithoutResult(status -> {
                stepMapper.updateById(step);
                turnMapper.updateById(turn);
                sceneSelectionStore.clear(conversationId);
            });
            return null;
        }
        return new WaitingInput(turn, step);
    }

    private GroupChatEvent waitingEvent(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step) {
        GroupChatEvent.GroupChatEventBuilder builder =
                GroupChatEvent.builder()
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
                        .name("用户")
                        .build());
        if (GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                step.getActionType())
                && sceneSelectionStore != null) {
            Map<String, String> names =
                    new java.util.LinkedHashMap<>();
            sceneSelectionStore.getOptions(
                            conversation.getId(), turn.getId())
                    .forEach((number, option) ->
                            names.put(number, option.name()));
            builder.sceneOptions(
                    java.util.Collections.unmodifiableMap(names));
        }
        return builder.build();
    }

    private Flux<GroupChatEvent> complete(
            GroupConversation conversation, GroupChatTurn turn) {
        return Flux.defer(() -> {
            transactionTemplate.executeWithoutResult(status -> {
                planResolver.onTurnCompleted(
                        conversation, turn.getPlanSource());
                turn.setStatus(GroupChatConstant.STATUS_COMPLETED)
                        .setUpdatedAt(LocalDateTime.now());
                turnMapper.updateById(turn);
            });
            return Flux.just(GroupChatEvent.builder()
                    .eventType(GroupChatConstant.EVENT_TURN_COMPLETED)
                    .conversationId(conversation.getId())
                    .turnId(turn.getId())
                    .build());
        });
    }

    private int firstUserStep(List<GroupChatReplyStep> steps) {
        for (int index = 0; index < steps.size(); index++) {
            if (GroupChatConstant.ACTOR_USER.equals(
                    steps.get(index).getSpeakerType())) {
                return index;
            }
        }
        return -1;
    }

    private void validateStartRequest(GroupTurnStartDTO request) {
        if (request == null) {
            throw new UserRequestException("行动轮请求不能为空");
        }
        if (request.getClientRequestId() != null
                && request.getClientRequestId().length() > 100) {
            throw new UserRequestException(
                    "clientRequestId长度不能超过100");
        }
    }

    private void validateMessageRequest(GroupChatRequestDTO request) {
        if (request == null
                || !StringUtils.hasText(request.getContent())) {
            throw new UserRequestException("用户行动内容不能为空");
        }
        if (request.getContent().length() > 4000) {
            throw new UserRequestException(
                    "用户行动内容不能超过4000个字符");
        }
    }

    private void requireTrpg(GroupConversation conversation) {
        if (!GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException(
                    "只有TRPG群聊支持行动轮接口");
        }
    }

    private record PreparedTurn(
            GroupChatTurn turn,
            List<GroupChatReplyStep> steps) {
    }

    private record WaitingInput(
            GroupChatTurn turn,
            GroupChatReplyStep step) {
    }
}
