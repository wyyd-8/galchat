package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import java.util.List;
import java.util.Objects;

final class GroupConversationSaveSnapshotPruner {

    private GroupConversationSaveSnapshotPruner() {
    }

    static boolean prune(UserWorldSaveSnapshotDTO snapshot,
                         Long conversationId) {
        if (snapshot == null || conversationId == null) {
            return false;
        }
        boolean changed = false;
        List<UserWorldSaveSnapshotDTO.GroupConversationTurnsSnapshot>
                turns = snapshot.getRecentGroupTurnsByConversation();
        if (turns != null) {
            List<UserWorldSaveSnapshotDTO.GroupConversationTurnsSnapshot>
                    retainedTurns = turns.stream()
                    .filter(item -> item == null || !Objects.equals(
                            conversationId, item.getConversationId()))
                    .toList();
            if (retainedTurns.size() != turns.size()) {
                snapshot.setRecentGroupTurnsByConversation(retainedTurns);
                changed = true;
            }
        }
        List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot>
                plans = snapshot.getConversationPlans();
        if (plans != null) {
            List<UserWorldSaveSnapshotDTO.GroupConversationPlanSnapshot>
                    retainedPlans = plans.stream()
                    .filter(item -> item == null || !Objects.equals(
                            conversationId, item.getConversationId()))
                    .toList();
            if (retainedPlans.size() != plans.size()) {
                snapshot.setConversationPlans(retainedPlans);
                changed = true;
            }
        }
        return changed;
    }
}
