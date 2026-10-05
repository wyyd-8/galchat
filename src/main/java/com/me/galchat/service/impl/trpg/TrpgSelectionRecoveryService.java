package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpSceneSelectionUndo;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.utils.RedisAfterCommitCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TrpgSelectionRecoveryService {
    private final GroupConversationMapper conversations;
    private final GroupReplyPlanMapper plans;
    private final GroupReplyPlanItemMapper items;
    private final GroupChatReplyStepMapper steps;
    private final TrpgSceneSelectionStore store;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long runId, Long turnId, String result) {
        if (result == null || !result.stripLeading().startsWith("{")) return;
        var node = json.readTree(result).get("undo");
        if (node == null || node.isNull()) throw new IllegalStateException("选景记录缺少撤销数据");
        var undo = json.treeToValue(node, KpSceneSelectionUndo.class);
        if (!Objects.equals(runId, undo.runId()) || !Objects.equals(turnId, undo.turnId())
                || undo.before() == null || undo.after() == null || undo.redisBefore() == null
                || undo.redisBefore().options() == null || undo.redisBefore().selections() == null
                || !Objects.equals(turnId, undo.redisBefore().turnId()) || undo.pendingSteps() == null) {
            throw new IllegalStateException("选景撤销上下文不完整");
        }
        var conversation = conversations.selectById(runId);
        if (conversation == null || !Objects.equals(conversation.getActiveReplyPlanId(), undo.createdPlanId())
                || !Objects.equals(conversation.getGameDayNo(), undo.after().day())
                || !Objects.equals(conversation.getGameTimePeriod(), undo.after().period())
                || !Objects.equals(conversation.getGameTimeRevision(), undo.after().revision())
                || !Objects.equals(conversation.getGameTimeChangedStepId(), undo.after().changedStepId())) {
            throw new IllegalStateException("选景状态已变化，无法安全回滚");
        }
        if (undo.createdPlanId() != null) {
            var plan = plans.selectById(undo.createdPlanId());
            if (plan == null || !Objects.equals(plan.getConversationId(), runId)
                    || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(plan.getSource())
                    || plan.getParentPlanId() != null || plan.getNextPlanId() != null) {
                throw new IllegalStateException("自动创建的场景已变化，无法安全回滚");
            }
            items.delete(new LambdaQueryWrapper<GroupReplyPlanItem>().eq(GroupReplyPlanItem::getPlanId, plan.getId()));
            requireWrite(plans.deleteById(plan.getId()));
        }
        for (var prior : undo.pendingSteps()) {
            var step = prior == null ? null : steps.selectById(prior.stepId());
            if (step == null || !Objects.equals(step.getTurnId(), turnId)) throw new IllegalStateException("选景步骤已变化");
            if (GroupChatConstant.STATUS_PENDING.equals(step.getStatus())) continue;
            if (!GroupChatConstant.STATUS_CANCELLED.equals(step.getStatus()) || !"单地点已自动分配".equals(step.getErrorMessage())) {
                throw new IllegalStateException("选景步骤已变化，无法安全回滚");
            }
            requireWrite(steps.update(null, new LambdaUpdateWrapper<GroupChatReplyStep>().eq(GroupChatReplyStep::getId, step.getId())
                    .set(GroupChatReplyStep::getStatus, GroupChatConstant.STATUS_PENDING)
                    .set(GroupChatReplyStep::getErrorMessage, prior.errorBefore()).set(GroupChatReplyStep::getUpdatedAt, prior.updatedBefore())));
        }
        var before = undo.before();
        requireWrite(conversations.update(null, new LambdaUpdateWrapper<GroupConversation>()
                .eq(GroupConversation::getId, runId).set(GroupConversation::getActiveReplyPlanId, null)
                .set(GroupConversation::getGameDayNo, before.day()).set(GroupConversation::getGameTimePeriod, before.period())
                .set(GroupConversation::getGameTimeRevision, before.revision())
                .set(GroupConversation::getGameTimeChangedStepId, before.changedStepId())
                .set(GroupConversation::getGameTimeUpdatedAt, before.timeUpdatedAt()).set(GroupConversation::getUpdatedAt, before.updatedAt())));
        RedisAfterCommitCleanup.run("恢复选景状态", () -> store.restore(runId, undo.redisBefore()));
    }
    private void requireWrite(int count) {
        if (count != 1) throw new IllegalStateException("选景回滚失败");
    }
}
