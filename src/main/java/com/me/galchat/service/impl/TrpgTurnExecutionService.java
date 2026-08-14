package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupSceneSelectionDTO;
import com.me.galchat.domain.dto.GroupEndExplorationDTO;
import com.me.galchat.domain.dto.GroupTurnContinueDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.GroupCurrentTurnVO;
import com.me.galchat.domain.vo.GroupCurrentTurnStepVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupRuntimeRegistry;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.service.ITrpgSaveService;
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
    private final TrpgSceneSelectionService sceneSelectionService;
    private final TrpgSceneLifecycleService sceneLifecycleService;
    private final TrpgSceneSelectionStore sceneSelectionStore;
    private final TrpgParticipantService participantService;
    private final GroupAgentDecisionStore decisionStore;
    private final DiceRollSummaryMapper diceRollSummaryMapper;
    private final com.me.galchat.groupchat.dice.DiceRollMessageCodec
            diceMessageCodec;
    private final TrpgCombatLifecycleService combatLifecycleService;
    private final GroupReplyPlanMapper replyPlanMapper;
    private final GroupTurnCheckpointService checkpointService;
    private final TrpgUnconsciousRecoveryService
            unconsciousRecoveryService;
    private final ITrpgSaveService trpgSaveService;

    public Flux<GroupChatEvent> continueTurn(
            Long conversationId,
            GroupTurnContinueDTO request) {
        return Flux.defer(() -> {
            validateContinueRequest(request);
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                assertClientRequestIdAvailable(
                        conversationId,
                        request.getClientRequestId());
                GroupConversation conversation =
                        conversationService.requireActive(
                                conversationId);
                requireTrpg(conversation);
                GroupChatTurn turn = latestRecoverableTurn(
                        conversationId);
                if (turn != null
                        && GroupChatConstant.STATUS_RUNNING.equals(
                        turn.getStatus())
                        && hasRunningStep(turn.getId())) {
                    recoveryService.recoverInterrupted(
                            conversationId);
                    turn = latestRecoverableTurn(conversationId);
                }
                if (invalidateExpiredSceneSelection(
                        conversationId, turn)) {
                    turn = null;
                }
                if (turn == null
                        || GroupChatConstant.STATUS_COMPLETED.equals(
                        turn.getStatus())) {
                    GroupChatTurn previousTurn = turn;
                    PreparedTurn prepared =
                            transactionTemplate.execute(status -> {
                                trpgSaveService.saveBeforeTurn(conversation);
                                if (previousTurn != null
                                        && GroupChatConstant
                                        .PLAN_SOURCE_COMBAT.equals(
                                        activePlanSource(conversation))
                                        && GroupChatConstant
                                        .PLAN_SOURCE_COMBAT.equals(
                                        previousTurn.getPlanSource())) {
                                    combatLifecycleService
                                            .startNextRoundUnderLock(
                                                    conversation);
                                }
                                return createTurn(
                                        conversation, request);
                            });
                    turn = prepared.turn();
                } else if (!GroupChatConstant.STATUS_RUNNING.equals(
                        turn.getStatus())) {
                    resumeTurn(turn);
                }
                GroupChatTurn selected = turn;
                Flux<GroupChatEvent> accepted = Flux.just(
                        GroupChatEvent.builder()
                                .eventType(GroupChatConstant
                                        .EVENT_TURN_ACCEPTED)
                                .conversationId(conversationId)
                                .turnId(selected.getId())
                                .build());
                return Flux.concat(accepted,
                                executePendingSteps(
                                        conversation, selected))
                        .doOnError(error ->
                                recoveryService.recoverInterrupted(
                                        conversationId))
                        .doFinally(signal ->
                                lockService.unlock(lock));
            } catch (RuntimeException exception) {
                lockService.unlock(lock);
                return Flux.error(exception);
            }
        });
    }

    @Autowired
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
            TransactionTemplate transactionTemplate,
            TrpgSceneSelectionService sceneSelectionService,
            TrpgSceneLifecycleService sceneLifecycleService,
            TrpgSceneSelectionStore sceneSelectionStore,
            TrpgParticipantService participantService,
            GroupAgentDecisionStore decisionStore,
            DiceRollSummaryMapper diceRollSummaryMapper,
            com.me.galchat.groupchat.dice.DiceRollMessageCodec
                    diceMessageCodec,
            TrpgCombatLifecycleService combatLifecycleService,
            GroupReplyPlanMapper replyPlanMapper,
            GroupTurnCheckpointService checkpointService,
            TrpgUnconsciousRecoveryService
                    unconsciousRecoveryService,
            ITrpgSaveService trpgSaveService) {
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
        this.sceneSelectionService = sceneSelectionService;
        this.sceneLifecycleService = sceneLifecycleService;
        this.sceneSelectionStore = sceneSelectionStore;
        this.participantService = participantService;
        this.decisionStore = decisionStore;
        this.diceRollSummaryMapper = diceRollSummaryMapper;
        this.diceMessageCodec = diceMessageCodec;
        this.combatLifecycleService = combatLifecycleService;
        this.replyPlanMapper = replyPlanMapper;
        this.checkpointService = checkpointService;
        this.unconsciousRecoveryService =
                unconsciousRecoveryService;
        this.trpgSaveService = trpgSaveService;
    }

    public Flux<GroupChatEvent> retry(
            Long conversationId, Long turnId, Long stepId) {
        return Flux.defer(() -> {
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                GroupConversation conversation =
                        conversationService.requireActive(
                                conversationId);
                requireTrpg(conversation);
                GroupChatTurn turn = requireFailedTurn(
                        conversationId, turnId);
                GroupChatReplyStep failedStep =
                        requireRetryableStep(turnId, stepId);
                boolean wholeTurnRestarted = Boolean.TRUE.equals(
                        transactionTemplate.execute(status ->
                                restoreFailedStep(turn, failedStep)));
                List<GroupChatReplyStep> remaining =
                        stepMapper.selectList(
                                new LambdaQueryWrapper<
                                        GroupChatReplyStep>()
                                        .eq(GroupChatReplyStep::getTurnId,
                                                turnId)
                                        .ge(!wholeTurnRestarted,
                                                GroupChatReplyStep::getStepNo,
                                                failedStep.getStepNo())
                                        .eq(GroupChatReplyStep::getStatus,
                                                GroupChatConstant
                                                        .STATUS_PENDING)
                                        .orderByAsc(
                                                GroupChatReplyStep
                                                        ::getStepNo));
                Flux<GroupChatEvent> accepted = Flux.just(
                        GroupChatEvent.builder()
                                .eventType(GroupChatConstant
                                        .EVENT_TURN_ACCEPTED)
                                .conversationId(conversationId)
                                .turnId(turnId)
                                .replyStepId(stepId)
                                .build());
                return Flux.concat(accepted,
                                executeScheduledSteps(
                                        conversation, turn,
                                        remaining, 0))
                        .doOnError(error ->
                                recoveryService.recoverInterrupted(
                                        conversationId))
                        .doFinally(signal ->
                                lockService.unlock(lock));
            } catch (RuntimeException exception) {
                lockService.unlock(lock);
                return Flux.error(exception);
            }
        });
    }

    private GroupChatTurn requireFailedTurn(
            Long conversationId, Long turnId) {
        GroupChatTurn turn = turnMapper.selectById(turnId);
        if (turn == null
                || !conversationId.equals(
                        turn.getConversationId())) {
            throw new UserRequestException("行动轮不存在");
        }
        if (!GroupChatConstant.STATUS_FAILED.equals(
                turn.getStatus())) {
            throw new UserRequestException(
                    "只能重试失败的行动轮");
        }
        return turn;
    }

    private GroupChatTurn latestRecoverableTurn(
            Long conversationId) {
        List<GroupChatTurn> turns = turnMapper.selectList(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId,
                                conversationId)
                        .in(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_COMPLETED,
                                GroupChatConstant.STATUS_RUNNING,
                                GroupChatConstant.STATUS_WAITING_INPUT,
                                GroupChatConstant.STATUS_PAUSED,
                                GroupChatConstant.STATUS_WAITING_DICE,
                                GroupChatConstant.STATUS_FAILED,
                                GroupChatConstant.STATUS_BLOCKED)
                        .orderByDesc(GroupChatTurn::getId)
                        .last("limit 1"));
        return turns == null || turns.isEmpty()
                ? null : turns.getFirst();
    }

    private boolean hasRunningStep(Long turnId) {
        Long count = stepMapper.selectCount(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .eq(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_RUNNING));
        return count != null && count > 0;
    }

    private boolean invalidateExpiredSceneSelection(
            Long conversationId, GroupChatTurn turn) {
        if (turn == null
                || !GroupChatConstant.STATUS_WAITING_INPUT.equals(
                turn.getStatus())) {
            return false;
        }
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .eq(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_WAITING_INPUT)
                        .orderByAsc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        GroupChatReplyStep step = steps.getFirst();
        if (!GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                step.getActionType())
                || !sceneSelectionStore.getOptions(
                        conversationId, turn.getId()).isEmpty()) {
            return false;
        }
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
        return true;
    }

    private String activePlanSource(
            GroupConversation conversation) {
        if (conversation.getActiveReplyPlanId() == null) {
            return null;
        }
        var plan = replyPlanMapper.selectById(
                conversation.getActiveReplyPlanId());
        return plan == null ? null : plan.getSource();
    }

    private void resumeTurn(GroupChatTurn turn) {
        if (GroupChatConstant.STATUS_WAITING_INPUT.equals(
                turn.getStatus())) {
            throw new UserRequestException(
                    "当前等待用户调查员行动，请使用用户行动接口");
        }
        List<GroupChatReplyStep> activeSteps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .in(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_PAUSED,
                                GroupChatConstant.STATUS_WAITING_DICE,
                                GroupChatConstant.STATUS_FAILED)
                        .orderByAsc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        GroupChatReplyStep step =
                activeSteps == null || activeSteps.isEmpty()
                        ? null : activeSteps.getFirst();
        if (GroupChatConstant.STATUS_WAITING_DICE.equals(
                turn.getStatus())) {
            if (step == null || hasPendingDice(step.getId())) {
                throw new UserRequestException(
                        "当前仍有用户骰点未完成");
            }
        }
        if (GroupChatConstant.STATUS_FAILED.equals(turn.getStatus())
                || GroupChatConstant.STATUS_BLOCKED.equals(
                turn.getStatus())) {
            if (step == null) {
                List<GroupChatReplyStep> failed =
                        stepMapper.selectList(
                                new LambdaQueryWrapper<
                                        GroupChatReplyStep>()
                                        .eq(GroupChatReplyStep::getTurnId,
                                                turn.getId())
                                        .eq(GroupChatReplyStep::getStatus,
                                                GroupChatConstant
                                                        .STATUS_FAILED)
                                        .orderByAsc(
                                                GroupChatReplyStep
                                                        ::getStepNo)
                                        .last("limit 1"));
                step = failed == null || failed.isEmpty()
                        ? null : failed.getFirst();
            }
            if (step == null) {
                throw new UserRequestException(
                        "失败行动轮没有可恢复的步骤");
            }
            restoreFailedStep(turn, step);
            return;
        }
        if (step == null) {
            throw new UserRequestException(
                    "当前行动轮没有可继续的暂停步骤");
        }
        LocalDateTime now = LocalDateTime.now();
        step.setStatus(GroupChatConstant.STATUS_PENDING)
                .setErrorMessage(null)
                .setUpdatedAt(now);
        persistRetryableStep(step);
        turn.setStatus(GroupChatConstant.STATUS_RUNNING)
                .setUpdatedAt(now);
        turnMapper.updateById(turn);
    }

    private boolean hasPendingDice(Long replyStepId) {
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getReplyStepId,
                                replyStepId)
                        .eq(GroupChatMessage::getMessageKind,
                                GroupChatConstant.MESSAGE_DICE_ROLL)
                        .orderByDesc(GroupChatMessage::getId)
                        .last("limit 1"));
        if (messages == null || messages.isEmpty()) {
            return true;
        }
        Long summaryId = diceMessageCodec.decode(
                messages.getFirst().getContent()).summaryId();
        DiceRollSummary summary =
                diceRollSummaryMapper.selectById(summaryId);
        return summary == null
                || DiceRollConstant.STATUS_PENDING.equals(
                summary.getStatus());
    }

    private GroupChatReplyStep requireRetryableStep(
            Long turnId, Long stepId) {
        GroupChatReplyStep step = stepMapper.selectById(stepId);
        if (step == null
                || !turnId.equals(step.getTurnId())) {
            throw new UserRequestException("回复步骤不存在");
        }
        if (!GroupChatConstant.STATUS_FAILED.equals(
                step.getStatus())
                || !GroupChatConstant.ACTOR_CHARACTER.equals(
                step.getSpeakerType())
                || !(GroupChatConstant.ACTION_TRPG_SCENE.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_TRPG_COMBAT.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_COMBAT_DEFENSE.equals(
                step.getActionType()))) {
            throw new UserRequestException(
                    "只能重试失败的角色行动步骤");
        }
        return step;
    }

    private boolean restoreFailedStep(
            GroupChatTurn turn, GroupChatReplyStep failedStep) {
        boolean restored = checkpointService.restore(
                turn, failedStep);
        if (!restored) {
            List<GroupChatReplyStep> allSteps = stepMapper.selectList(
                    new LambdaQueryWrapper<GroupChatReplyStep>()
                            .eq(GroupChatReplyStep::getTurnId,
                                    turn.getId())
                            .orderByAsc(GroupChatReplyStep::getStepNo));
            if (allSteps != null) {
                for (GroupChatReplyStep step : allSteps) {
                    decisionStore.deleteByReplyStepId(step.getId());
                    combatLifecycleService.clearControlMarkersForRetry(
                            step.getId());
                }
            }
            sceneSelectionStore.clear(turn.getConversationId());
            return true;
        }
        decisionStore.deleteByReplyStepId(failedStep.getId());
        combatLifecycleService.clearControlMarkersForRetry(
                failedStep.getId());
        LocalDateTime now = LocalDateTime.now();
        List<GroupChatReplyStep> blockedTail =
                stepMapper.selectList(
                        new LambdaQueryWrapper<
                                GroupChatReplyStep>()
                                .eq(GroupChatReplyStep::getTurnId,
                                        turn.getId())
                                .gt(GroupChatReplyStep::getStepNo,
                                        failedStep.getStepNo())
                                .eq(GroupChatReplyStep::getStatus,
                                        GroupChatConstant
                                                .STATUS_BLOCKED)
                                .orderByAsc(
                                        GroupChatReplyStep
                                                ::getStepNo));
        for (GroupChatReplyStep blocked : blockedTail) {
            blocked.setStatus(GroupChatConstant.STATUS_PENDING)
                    .setErrorMessage(null)
                    .setUpdatedAt(now);
            persistRetryableStep(blocked);
        }
        if (!GroupChatConstant.STATUS_WAITING_DICE.equals(
                turn.getStatus())) {
            turn.setStatus(GroupChatConstant.STATUS_RUNNING)
                    .setUpdatedAt(now);
            turnMapper.updateById(turn);
        }
        return false;
    }

    private void persistRetryableStep(GroupChatReplyStep step) {
        stepMapper.update(null,
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getId, step.getId())
                        .set(GroupChatReplyStep::getStatus,
                                step.getStatus())
                        .set(GroupChatReplyStep::getErrorMessage, null)
                        .set(GroupChatReplyStep::getUpdatedAt,
                                step.getUpdatedAt()));
    }

    public GroupCurrentTurnVO current(Long conversationId) {
        GroupConversation conversation =
                conversationService.requireAuthorized(conversationId);
        List<GroupChatTurn> turns = turnMapper.selectList(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId,
                                conversationId)
                        .in(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_RUNNING,
                                GroupChatConstant.STATUS_WAITING_INPUT,
                                GroupChatConstant.STATUS_PAUSED,
                                GroupChatConstant.STATUS_WAITING_DICE,
                                GroupChatConstant.STATUS_FAILED,
                                GroupChatConstant.STATUS_BLOCKED)
                        .orderByDesc(GroupChatTurn::getId)
                        .last("limit 1"));
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        GroupChatTurn turn = turns.getFirst();
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .orderByAsc(GroupChatReplyStep::getStepNo));
        List<GroupChatReplyStep> allSteps = steps == null
                ? List.of() : steps;
        GroupChatReplyStep step = allSteps.stream()
                .filter(this::isCurrentStep)
                .findFirst()
                .orElse(null);
        boolean waiting = step != null
                && GroupChatConstant.STATUS_WAITING_INPUT.equals(
                        step.getStatus())
                && GroupChatConstant.ACTOR_USER.equals(
                        step.getSpeakerType());
        String inputType = GroupChatConstant.STATUS_PAUSED.equals(
                turn.getStatus()) ? "continue"
                : GroupChatConstant.STATUS_WAITING_DICE.equals(
                turn.getStatus()) ? "dice"
                : step == null ? null
                : GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                        step.getActionType())
                ? "selection" : "message";
        Map<String, String> options = Map.of();
        if ("selection".equals(inputType)) {
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
                step == null ? null : step.getItemOrder(),
                inputType,
                step == null ? null : step.getGroupName(),
                waiting,
                options,
                allSteps.stream()
                        .map(item -> new GroupCurrentTurnStepVO(
                                item.getId(), item.getItemOrder(),
                                item.getSpeakerType(), item.getSpeakerId(),
                                item.getSubjectCharacterId(),
                                item.getStatus(), item.getErrorMessage()))
                        .toList());
    }

    private boolean isCurrentStep(GroupChatReplyStep step) {
        return GroupChatConstant.STATUS_RUNNING.equals(step.getStatus())
                || GroupChatConstant.STATUS_WAITING_INPUT.equals(
                step.getStatus())
                || GroupChatConstant.STATUS_PAUSED.equals(step.getStatus())
                || GroupChatConstant.STATUS_WAITING_DICE.equals(
                step.getStatus())
                || GroupChatConstant.STATUS_FAILED.equals(step.getStatus())
                || GroupChatConstant.STATUS_BLOCKED.equals(step.getStatus());
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
                assertClientRequestIdAvailable(
                        conversationId,
                        request.getClientRequestId());
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
            validateClientRequestId(request.getClientRequestId());
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                assertClientRequestIdAvailable(
                        conversationId,
                        request.getClientRequestId());
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
                                        + ":" + choice.locationName(),
                                request.getClientRequestId()));
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
            GroupEndExplorationDTO request) {
        return Flux.defer(() -> {
            if (request == null) {
                throw new UserRequestException(
                        "结束探索请求不能为空");
            }
            validateClientRequestId(request.getClientRequestId());
            conversationService.requireAuthorized(conversationId);
            GroupConversationLockService.OwnedLock lock =
                    lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException(
                        "当前群聊正在执行行动轮，请稍后再试"));
            }
            try {
                assertClientRequestIdAvailable(
                        conversationId,
                        request.getClientRequestId());
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
                        GroupChatConstant.ACTOR_USER,
                        userStep.getSpeakerId());
                GroupChatMessage message = transactionTemplate.execute(
                        status -> completeStructuredUserStep(
                                conversation, turn, userStep,
                                "用户调查员已结束当前场景探索。",
                                request.getClientRequestId()));
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
        return Flux.concat(accepted,
                        executeScheduledSteps(
                                conversation, turn, remaining, 0))
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
                request.getContent().trim(),
                request.getClientRequestId());
    }

    private GroupChatMessage completeStructuredUserStep(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep userStep,
            String content,
            String clientRequestId) {
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setSceneId(GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                        turn.getPlanSource())
                        ? turn.getPlanContextId() : null)
                .setTurnId(turn.getId())
                .setReplyStepId(userStep.getId())
                .setClientRequestId(normalizeClientRequestId(
                        clientRequestId))
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
        checkpointService.recordBoundary(
                turn, userStep,
                GroupTurnCheckpointService.COMPLETED);
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
            GroupConversation conversation, GroupTurnContinueDTO request) {
        GroupModeRuntime runtime =
                runtimeRegistry.require(conversation.getMode());
        List<TrpgParticipantService.Participant> participants =
                participantService.listInvestigators(conversation);
        GroupTurnPlanResolver.ResolvedTurnPlan resolved =
                planResolver.resolve(conversation, runtime);
        List<GroupActionSpec> actions =
                withSceneIntro(conversation, resolved);
        int syntheticSteps = actions.stream().anyMatch(action ->
                GroupChatConstant.ACTION_TRPG_SCENE_INTRO.equals(
                        action.actionType())) ? 1 : 0;
        int actionLimit = GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                resolved.source())
                ? GroupChatConstant.MAX_REPLY_STEPS * 4
                : GroupChatConstant.MAX_REPLY_STEPS + syntheticSteps;
        if (actions.size() > actionLimit) {
            throw new UserRequestException(
                    "单次行动轮人物数量不能超过"
                            + GroupChatConstant.MAX_REPLY_STEPS);
        }
        LocalDateTime now = LocalDateTime.now();
        GroupChatTurn turn = new GroupChatTurn()
                .setConversationId(conversation.getId())
                .setClientRequestId(normalizeClientRequestId(
                        request.getClientRequestId()))
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
                    .setSubjectCharacterId(
                            action.subjectCharacterId())
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
        return List.of(intro);
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

    /**
     * Re-queries after every step so a hidden combat route can either cancel
     * its defense placeholder or bind it to the selected user/agent/NPC.
     */
    private Flux<GroupChatEvent> executePendingSteps(
            GroupConversation conversation,
            GroupChatTurn turn) {
        List<GroupChatReplyStep> pending = stepMapper.selectList(
                    new LambdaQueryWrapper<GroupChatReplyStep>()
                            .eq(GroupChatReplyStep::getTurnId,
                                    turn.getId())
                            .eq(GroupChatReplyStep::getStatus,
                                    GroupChatConstant.STATUS_PENDING)
                            .orderByAsc(
                                    GroupChatReplyStep::getStepNo));
        return executeScheduledSteps(
                conversation, turn,
                pending == null ? List.of() : pending, 0);
    }

    private Flux<GroupChatEvent> executeScheduledSteps(
            GroupConversation conversation,
            GroupChatTurn turn,
            List<GroupChatReplyStep> scheduled,
            int index) {
        return Flux.defer(() -> {
            String turnStatus = turn.getStatus();
            GroupChatTurn persistedTurn = turnMapper.selectById(turn.getId());
            if (persistedTurn != null) {
                turnStatus = persistedTurn.getStatus();
                turn.setStatus(turnStatus);
            }
            if (GroupChatConstant.STATUS_PAUSED.equals(turnStatus)
                    || GroupChatConstant.STATUS_WAITING_DICE.equals(
                    turnStatus)) {
                return Flux.just(pausedEvent(conversation, turn));
            }
            if (GroupChatConstant.STATUS_COMPLETED.equals(turnStatus)) {
                return Flux.just(GroupChatEvent.builder()
                        .eventType(
                                GroupChatConstant.EVENT_TURN_COMPLETED)
                        .conversationId(conversation.getId())
                        .turnId(turn.getId())
                        .build());
            }
            if (index >= scheduled.size()) {
                return complete(conversation, turn);
            }
            GroupChatReplyStep original = scheduled.get(index);
            GroupChatReplyStep persisted =
                    stepMapper.selectById(original.getId());
            GroupChatReplyStep next =
                    persisted == null ? original : persisted;
            if (GroupChatConstant.STATUS_CANCELLED.equals(
                    next.getStatus())
                    || GroupChatConstant.STATUS_COMPLETED.equals(
                    next.getStatus())) {
                return executeScheduledSteps(
                        conversation, turn, scheduled, index + 1);
            }
            TrpgUnconsciousRecoveryService.Execution recovery =
                    unconsciousRecoveryService.handle(
                            conversation, turn, next);
            if (recovery != null) {
                if (recovery.outcome()
                        == TrpgUnconsciousRecoveryService.Outcome.PAUSED) {
                    return Flux.concat(
                            Flux.fromIterable(recovery.events()),
                            Flux.just(pausedEvent(conversation, turn)));
                }
                if (recovery.outcome()
                        == TrpgUnconsciousRecoveryService.Outcome.SKIPPED
                        || recovery.outcome()
                        == TrpgUnconsciousRecoveryService.Outcome.COMPLETED) {
                    return executeScheduledSteps(
                            conversation, turn, scheduled, index + 1);
                }
            }
            if (GroupChatConstant.ACTOR_USER.equals(
                    next.getSpeakerType())) {
                return waitForUser(conversation, turn, next);
            }
            return groupChatService.streamPersistedStep(
                            conversation, turn, next)
                    .concatWith(Flux.defer(() ->
                            executeScheduledSteps(
                                    conversation, turn,
                                    scheduled, index + 1)));
        });
    }

    private GroupChatEvent pausedEvent(
            GroupConversation conversation,
            GroupChatTurn turn) {
        return GroupChatEvent.builder()
                .eventType(GroupChatConstant.EVENT_TURN_PAUSED)
                .conversationId(conversation.getId())
                .turnId(turn.getId())
                .build();
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
                step.getActionType())) {
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
                        conversation, turn);
                turn.setStatus(GroupChatConstant.STATUS_COMPLETED)
                        .setUpdatedAt(LocalDateTime.now());
                turnMapper.updateById(turn);
                checkpointService.clear(conversation.getId());
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

    private void validateContinueRequest(GroupTurnContinueDTO request) {
        if (request == null) {
            throw new UserRequestException("行动轮请求不能为空");
        }
        validateClientRequestId(request.getClientRequestId());
    }

    private void validateClientRequestId(String clientRequestId) {
        if (clientRequestId != null
                && clientRequestId.length() > 100) {
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
        validateClientRequestId(request.getClientRequestId());
    }

    private String normalizeClientRequestId(String clientRequestId) {
        return StringUtils.hasText(clientRequestId)
                ? clientRequestId.trim() : null;
    }

    private void assertClientRequestIdAvailable(
            Long conversationId, String clientRequestId) {
        String normalized =
                normalizeClientRequestId(clientRequestId);
        if (normalized == null) {
            return;
        }
        Long turnCount = turnMapper.selectCount(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId,
                                conversationId)
                        .eq(GroupChatTurn::getClientRequestId,
                                normalized));
        Long messageCount = messageMapper.selectCount(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId,
                                conversationId)
                        .eq(GroupChatMessage::getClientRequestId,
                                normalized));
        if ((turnCount != null && turnCount > 0)
                || (messageCount != null && messageCount > 0)) {
            throw new UserRequestException(
                    "clientRequestId已处理，请勿重复提交");
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

}
