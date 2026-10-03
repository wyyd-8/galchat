package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpSceneFinishDTOs;
import com.me.galchat.domain.dto.InvestigatorSceneFinishResult;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.utils.RedisAfterCommitCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Checkpoint recovery needs persistence only, without the scene summarizer's chat client. */
@Service
@RequiredArgsConstructor
public class TrpgSceneFinishRecoveryService {
    private final GroupReplyPlanMapper planMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final TrpgSceneProgressStore progressStore;

    @Transactional(rollbackFor = Exception.class)
    public void rollbackKpFinish(Long conversationId, KpSceneFinishDTOs.FinishResult result) {
        rollbackFinish(conversationId, result, "KP已结束当前场景探索");
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackInvestigatorFinish(Long conversationId, InvestigatorSceneFinishResult result) {
        if (result == null || result.characterId() == null) {
            throw new IllegalStateException("结束探索记录缺少撤销数据，无法回滚");
        }
        rollbackFinish(conversationId, new KpSceneFinishDTOs.FinishResult(result.message(),
                result.scenePlanId(), result.turnId(), result.finishRequestedBefore(), result.cancelledSteps()),
                "所有调查员已结束当前场景探索");
        if (result.readyBefore()) {
            RedisAfterCommitCleanup.run("恢复调查员结束探索标记", () ->
                    progressStore.markReady(conversationId, result.scenePlanId(), result.characterId()));
        } else {
            progressStore.clearReady(conversationId, result.scenePlanId(), result.characterId());
        }
    }

    private void rollbackFinish(Long conversationId, KpSceneFinishDTOs.FinishResult result, String reason) {
        if (result == null || result.scenePlanId() == null || result.turnId() == null || result.cancelledSteps() == null) {
            throw new IllegalStateException("结束场景记录缺少撤销数据，无法回滚");
        }
        GroupReplyPlan scene = planMapper.selectById(result.scenePlanId());
        GroupChatTurn turn = turnMapper.selectById(result.turnId());
        if (scene == null || !Objects.equals(conversationId, scene.getConversationId())
                || turn == null || !Objects.equals(turn.getConversationId(), conversationId)
                || !Objects.equals(turn.getPlanId(), scene.getId())) {
            throw new IllegalStateException("结束场景上下文已变化，无法回滚");
        }
        for (var undo : result.cancelledSteps()) {
            var step = undo == null || undo.stepId() == null ? null : stepMapper.selectById(undo.stepId());
            if (step == null || !Objects.equals(step.getTurnId(), turn.getId())
                    || !GroupChatConstant.STATUS_CANCELLED.equals(step.getStatus())
                    || !reason.equals(step.getErrorMessage())) {
                throw new IllegalStateException("结束场景取消的步骤已变化，无法安全回滚");
            }
        }
        for (var undo : result.cancelledSteps()) {
            int updated = stepMapper.update(null, new LambdaUpdateWrapper<GroupChatReplyStep>()
                    .eq(GroupChatReplyStep::getId, undo.stepId()).eq(GroupChatReplyStep::getTurnId, result.turnId())
                    .eq(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_CANCELLED)
                    .set(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_PENDING)
                    .set(GroupChatReplyStep::getErrorMessage, undo.errorBefore())
                    .set(GroupChatReplyStep::getUpdatedAt, undo.updatedBefore()));
            if (updated != 1) throw new IllegalStateException("结束场景取消的步骤回滚失败");
        }
        RedisAfterCommitCleanup.run("恢复场景结束标记", () -> {
            if (result.finishRequestedBefore()) progressStore.requestFinish(conversationId, result.scenePlanId());
            else progressStore.clearFinish(conversationId, result.scenePlanId());
        });
    }

}
