package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TrpgRunLifecycleService {

    private final GroupConversationService conversationService;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final com.me.galchat.mapper.TrpgCompletionMapper completionMapper;

    public void requestFinish(
            Long conversationId, Long replyStepId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        if (!GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("只有TRPG群聊可以结束跑团");
        }
        GroupChatReplyStep step =
                stepMapper.selectById(replyStepId);
        GroupChatTurn turn = step == null
                ? null : turnMapper.selectById(step.getTurnId());
        if (step == null || turn == null
                || !Objects.equals(
                        turn.getConversationId(), conversationId)) {
            throw new UserRequestException("当前回复步骤不属于该跑团");
        }
        if (!GroupChatConstant.ACTOR_KP.equals(
                step.getSpeakerType())) {
            throw new UserAuthException("只有KP可以结束整个跑团");
        }
        if (!(GroupChatConstant.ACTION_TRPG_SCENE.equals(step.getActionType())
                && GroupChatConstant.PLAN_SOURCE_SCENE.equals(turn.getPlanSource()))
                && !(GroupChatConstant.ACTION_TRPG_POST_COMBAT_TRANSITION.equals(step.getActionType())
                && GroupChatConstant.PLAN_SOURCE_POST_COMBAT.equals(turn.getPlanSource()))) {
            throw new UserRequestException("只有探索或战斗结束后的KP步骤可以结束跑团");
        }
        if (conversation.getActiveReplyPlanId() == null
                || !Objects.equals(conversation.getActiveReplyPlanId(), turn.getPlanId())) {
            throw new UserRequestException("当前回复计划已变化，不能结束跑团");
        }
        if (!GroupChatConstant.STATUS_RUNNING.equals(step.getStatus())
                || !GroupChatConstant.STATUS_RUNNING.equals(turn.getStatus())) {
            throw new UserRequestException("只能在当前执行中的KP步骤结束跑团");
        }
        if (completionMapper.selectById(conversationId) == null) {
            completionMapper.insert(new com.me.galchat.domain.po.TrpgCompletion()
                    .setConversationId(conversationId).setTurnId(turn.getId()));
        }
    }

    public boolean hasFinishRequest(GroupConversation conversation, Long turnId) {
        if (conversation == null || !GroupChatConstant.MODE_TRPG.equals(conversation.getMode())) return false;
        var completion = completionMapper.selectById(conversation.getId());
        if (completion == null || !Objects.equals(completion.getTurnId(), turnId)) return false;
        // A retained finishRun tool call may belong to a paused/failed KP reply. Resume that
        // reply first so its final public narrative is included in the closing scene summary.
        Long completed = stepMapper.countCompletedRunFinishByTurn(turnId);
        return completed != null && completed > 0;
    }

    public boolean isSummaryPending(GroupConversation conversation) {
        return conversation.getActiveReplyPlanId() == null
                && completionMapper.selectById(conversation.getId()) != null;
    }
}
