package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GroupTurnPlanResolver {

    private final GroupReplyPlanService replyPlanService;
    private final TrpgSceneSelectionService selectionService;
    private final TrpgSceneLifecycleService sceneLifecycleService;
    private final TrpgRunLifecycleService runLifecycleService;
    private final TrpgCombatLifecycleService combatLifecycleService;

    public ResolvedTurnPlan resolve(
            GroupConversation conversation, GroupModeRuntime runtime) {
        if (GroupChatConstant.MODE_TRPG.equals(conversation.getMode())
                && conversation.getActiveReplyPlanId() == null) {
            return new ResolvedTurnPlan(
                    GroupChatConstant.TURN_SOURCE_SCENE_SELECTION,
                    null,
                    selectionService.selectionActions(conversation));
        }
        GroupReplyPlanSelection selection =
                replyPlanService.currentGroupForExecution(conversation);
        List<GroupActionSpec> actions =
                runtime.turnPolicy().plan(conversation, selection);
        if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                selection.source())) {
            actions = sceneLifecycleService.remainingActions(
                    conversation.getId(),
                    selection.contextId(),
                    actions);
        }
        return new ResolvedTurnPlan(
                selection.source(),
                selection.contextId(),
                actions);
    }

    public void onTurnCompleted(
            GroupConversation conversation, String turnSource) {
        if (runLifecycleService.finalizeAfterTurn(conversation)) {
            return;
        }
        if (GroupChatConstant.TURN_SOURCE_SCENE_SELECTION.equals(turnSource)) {
            selectionService.finalizeSelections(conversation);
            return;
        }
        sceneLifecycleService.finalizeAfterTurn(conversation, turnSource);
    }

    public void onTurnCompleted(
            GroupConversation conversation,
            com.me.galchat.domain.po.GroupChatTurn turn) {
        if (combatLifecycleService.finalizeStartAfterTurn(
                conversation, turn)) {
            return;
        }
        onTurnCompleted(conversation, turn.getPlanSource());
    }

    public record ResolvedTurnPlan(
            String source,
            Long contextId,
            List<GroupActionSpec> actions) {
    }
}
