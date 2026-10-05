package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.group.GroupTurnRecoveryService;
import com.me.galchat.service.impl.group.GroupReplyPlanService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.dto.KpSceneFinishDTOs;
import com.me.galchat.domain.dto.InvestigatorSceneFinishResult;
import com.me.galchat.utils.RedisAfterCommitCleanup;
import org.springframework.transaction.annotation.Transactional;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgSceneLifecycleService {

    private final GroupConversationService conversationService;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final GroupTurnRecoveryService recoveryService;
    private final TrpgSceneProgressStore progressStore;
    private final TrpgSceneSummaryService summaryService;
    private final GroupReplyPlanService replyPlanService;
    private final TrpgChildScenePlanService childScenePlanService;
    private final TrpgTemporaryInsanityService temporaryInsanityService;
    private TrpgInvestigatorSuspensionService suspensionService;

    @Autowired(required = false)
    void setSuspensionService(
            TrpgInvestigatorSuspensionService suspensionService) {
        this.suspensionService = suspensionService;
    }

    public GroupReplyPlanSelection applyReadyStatuses(
            GroupReplyPlanSelection selection,
            Set<String> readyActors) {
        if (readyActors.isEmpty()) {
            return selection;
        }
        for (GroupReplyPlanItem item : selection.items()) {
            if ((GroupChatConstant.ACTOR_USER.equals(
                    item.getActorType())
                    || GroupChatConstant.ACTOR_CHARACTER.equals(
                            item.getActorType()))
                    && !GroupChatConstant.PARTICIPANT_WAITING.equals(
                            item.getParticipantStatus())
                    && readyActors.contains(
                            TrpgSceneProgressStore.actorKey(
                                    item.getSubjectCharacterId()))) {
                item.setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_READY);
            }
        }
        return selection;
    }

    public Set<String> readyActors(
            Long conversationId, Long scenePlanId) {
        return progressStore.readyActors(
                conversationId, scenePlanId);
    }

    @Transactional(rollbackFor = Exception.class)
    public InvestigatorSceneFinishResult requestInvestigatorFinish(
            Long conversationId, Long replyStepId, Long characterId) {
        return requestInvestigatorFinish(
                conversationId, replyStepId,
                GroupChatConstant.ACTOR_CHARACTER, characterId);
    }

    @Transactional(rollbackFor = Exception.class)
    public InvestigatorSceneFinishResult requestInvestigatorFinish(
            Long conversationId,
            Long replyStepId,
            String actorType,
            Long actorId) {
        SceneExecution execution = requireSceneExecution(
                conversationId, replyStepId);
        if (!Objects.equals(actorType,
                execution.step().getSpeakerType())
                || !Objects.equals(actorId,
                execution.step().getSpeakerId())
                || (!GroupChatConstant.ACTOR_CHARACTER.equals(actorType)
                && !GroupChatConstant.ACTOR_USER.equals(actorType))) {
            throw new UserAuthException(
                    "只能由当前调查员结束自己的场景探索");
        }
        if (execution.step().getSubjectCharacterId() == null) {
            throw new UserRequestException(
                    "当前调查员行动未绑定人物卡");
        }
        Set<String> participantActors = itemMapper.selectList(
                        new LambdaQueryWrapper<GroupReplyPlanItem>()
                                .eq(GroupReplyPlanItem::getPlanId,
                                        execution.plan().getId())
                                .in(GroupReplyPlanItem::getActorType,
                                        GroupChatConstant.ACTOR_USER,
                                        GroupChatConstant.ACTOR_CHARACTER))
                .stream()
                .filter(item -> item.getSubjectCharacterId() != null)
                .filter(item -> suspensionService == null
                        || !suspensionService.isUnavailable(
                                conversationId,
                                item.getSubjectCharacterId(),
                                execution.plan().getId()))
                .map(item -> TrpgSceneProgressStore.actorKey(
                        item.getSubjectCharacterId()))
                .collect(Collectors.toSet());
        Set<String> readyActors = new HashSet<>(progressStore.readyActors(
                conversationId, execution.plan().getId()));
        boolean readyBefore = readyActors.contains(TrpgSceneProgressStore.actorKey(
                execution.step().getSubjectCharacterId()));
        boolean finishBefore = progressStore.isFinishRequested(conversationId, execution.plan().getId());
        readyActors.add(TrpgSceneProgressStore.actorKey(
                execution.step().getSubjectCharacterId()));
        boolean allReady = !participantActors.isEmpty()
                && readyActors.containsAll(participantActors);
        List<KpSceneFinishDTOs.StepUndo> cancelled = allReady ? stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, execution.turn().getId())
                        .eq(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_PENDING)
                        .in(GroupChatReplyStep::getSpeakerType,
                                GroupChatConstant.ACTOR_USER, GroupChatConstant.ACTOR_CHARACTER))
                .stream().map(step -> new KpSceneFinishDTOs.StepUndo(
                        step.getId(), step.getErrorMessage(), step.getUpdatedAt())).toList() : List.of();
        if (allReady) {
            recoveryService.cancelPendingInvestigatorSteps(
                    execution.turn().getId(),
                    "所有调查员已结束当前场景探索");
        }
        Runnable publish = () -> {
            progressStore.markReady(conversationId, execution.plan().getId(),
                    execution.step().getSubjectCharacterId());
            if (allReady) {
                progressStore.requestFinish(conversationId, execution.plan().getId());
            }
        };
        // The user action and cancelled tail must commit before Redis advertises readiness.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
        return new InvestigatorSceneFinishResult(allReady
                ? "所有调查员均已结束探索，当前场景将进入结算。请用公开消息确认你的行动结束。"
                : "你的结束探索意向已记录。请用公开消息说明你已完成当前场景的行动。",
                execution.plan().getId(), execution.turn().getId(), execution.step().getSubjectCharacterId(),
                readyBefore, finishBefore, allReady, cancelled);
    }

    @Transactional(rollbackFor = Exception.class)
    public KpSceneFinishDTOs.FinishResult requestKpFinish(
            Long conversationId, Long replyStepId) {
        SceneExecution execution = requireSceneExecution(
                conversationId, replyStepId);
        if (!GroupChatConstant.ACTOR_KP.equals(
                execution.step().getSpeakerType())) {
            throw new UserAuthException("只有KP可以直接结束场景探索");
        }
        boolean finishBefore = progressStore.isFinishRequested(conversationId, execution.plan().getId());
        List<KpSceneFinishDTOs.StepUndo> cancelled = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, execution.turn().getId())
                        .eq(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_PENDING))
                .stream().map(step -> new KpSceneFinishDTOs.StepUndo(
                        step.getId(), step.getErrorMessage(), step.getUpdatedAt())).toList();
        recoveryService.cancelPendingSteps(execution.turn().getId(), "KP已结束当前场景探索");
        RedisAfterCommitCleanup.run("提交KP结束场景标记", () ->
                progressStore.requestFinish(conversationId, execution.plan().getId()));
        return new KpSceneFinishDTOs.FinishResult(
                "当前场景已请求结算；结束子场景不会影响父场景。公开消息只能说明‘XXX决定离开了XX’，不得加入后续前往场景的任何内容。",
                execution.plan().getId(), execution.turn().getId(), finishBefore, cancelled);
    }

    public boolean finalizeAfterTurn(
            GroupConversation conversation, String turnSource) {
        if (conversation == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                turnSource)
                || conversation.getActiveReplyPlanId() == null) {
            return false;
        }
        GroupReplyPlan plan = planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (plan == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                plan.getSource())) {
            return false;
        }
        Long sceneId = plan.getContextId();
        if (!progressStore.isFinishRequested(
                conversation.getId(), plan.getId())) {
            return false;
        }
        summaryService.summarize(
                conversation.getId(), sceneId, plan.getId());
        if (suspensionService != null) suspensionService.completeReentriesForScene(conversation.getId(), plan.getId());
        if (plan.getParentPlanId() != null) {
            childScenePlanService.finishChildUnderLock(
                    conversation, plan);
        } else {
            var nextPlan = replyPlanService.finishActiveUnderLock(
                    conversation);
            if (nextPlan == null) {
                temporaryInsanityService.advanceAfterLargeScene(
                        conversation.getId());
            }
        }
        progressStore.clear(conversation.getId(), plan.getId());
        return true;
    }

    public void closeRunScene(GroupConversation conversation, Long throughSequence) {
        Long planId = conversation.getActiveReplyPlanId();
        java.util.Set<Long> visited = new java.util.HashSet<>();
        boolean mainClosed = false;
        while (planId != null && visited.add(planId)) {
            GroupReplyPlan plan = planMapper.selectById(planId);
            if (plan == null || !java.util.Objects.equals(plan.getConversationId(), conversation.getId())) {
                throw new UserRequestException("结束跑团的主场景不存在");
            }
            if (GroupChatConstant.PLAN_SOURCE_POST_COMBAT.equals(plan.getSource())) {
                planId = plan.getResumePlanId();
                continue;
            }
            if (!GroupChatConstant.PLAN_SOURCE_SCENE.equals(plan.getSource())) {
                throw new UserRequestException("当前阶段不能结束跑团");
            }
            if (summaryService.summarizeThrough(conversation.getId(), plan.getContextId(), plan.getId(), throughSequence) == null) {
                throw new UserRequestException("最后的场景摘要未生成，请重试");
            }
            mainClosed = plan.getParentPlanId() == null;
            planId = plan.getParentPlanId();
        }
        if (!mainClosed) throw new UserRequestException("结束跑团的主场景不存在");
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishRunSceneUnderLock(GroupConversation conversation) {
        if (suspensionService != null) suspensionService.completeReentriesForRun(conversation.getId());
        replyPlanService.clearConversationPlans(conversation);
        temporaryInsanityService.advanceAfterLargeScene(conversation.getId());
    }

    private SceneExecution requireSceneExecution(
            Long conversationId, Long replyStepId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        GroupChatReplyStep step = stepMapper.selectById(replyStepId);
        GroupChatTurn turn = step == null
                ? null : turnMapper.selectById(step.getTurnId());
        if (step == null || turn == null
                || !Objects.equals(turn.getConversationId(), conversationId)
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                turn.getPlanSource())) {
            throw new UserRequestException("当前回复步骤不属于场景探索");
        }
        GroupReplyPlan plan = conversation.getActiveReplyPlanId() == null
                ? null : planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (plan == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                plan.getSource())
                || !Objects.equals(plan.getId(), turn.getPlanId())
                || !Objects.equals(
                plan.getContextId(), turn.getPlanContextId())) {
            throw new UserRequestException("当前场景回复计划已变化");
        }
        return new SceneExecution(step, turn, plan);
    }

    private record SceneExecution(
            GroupChatReplyStep step,
            GroupChatTurn turn,
            GroupReplyPlan plan) {
    }
}
