package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public boolean requestInvestigatorFinish(
            Long conversationId, Long replyStepId, Long characterId) {
        return requestInvestigatorFinish(
                conversationId, replyStepId,
                GroupChatConstant.ACTOR_CHARACTER, characterId);
    }

    public boolean requestInvestigatorFinish(
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
        progressStore.markReady(
                conversationId, execution.plan().getId(),
                execution.step().getSubjectCharacterId());
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
        Set<String> readyActors = progressStore.readyActors(
                conversationId, execution.plan().getId());
        if (!participantActors.isEmpty()
                && readyActors.containsAll(participantActors)) {
            progressStore.requestFinish(
                    conversationId, execution.plan().getId());
            recoveryService.cancelPendingInvestigatorSteps(
                    execution.turn().getId(),
                    "所有调查员已结束当前场景探索");
            return true;
        }
        return false;
    }

    public void requestKpFinish(
            Long conversationId, Long replyStepId) {
        SceneExecution execution = requireSceneExecution(
                conversationId, replyStepId);
        if (!GroupChatConstant.ACTOR_KP.equals(
                execution.step().getSpeakerType())) {
            throw new UserAuthException("只有KP可以直接结束场景探索");
        }
        progressStore.requestFinish(
                conversationId, execution.plan().getId());
        recoveryService.cancelPendingSteps(
                execution.turn().getId(), "KP已结束当前场景探索");
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
