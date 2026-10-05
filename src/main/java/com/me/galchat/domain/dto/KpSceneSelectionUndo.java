package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.service.impl.trpg.TrpgSceneSelectionStore;
import java.time.LocalDateTime;
import java.util.List;

public record KpSceneSelectionUndo(Long runId, Long turnId, TimeState before, TimeState after,
        Long createdPlanId, TrpgSceneSelectionStore.Snapshot redisBefore,
        List<KpSceneFinishDTOs.StepUndo> pendingSteps) {
    public record TimeState(Integer day, String period, Integer revision, Long changedStepId,
                            LocalDateTime timeUpdatedAt, LocalDateTime updatedAt) {
        public static TimeState of(GroupConversation c) {
            return new TimeState(c.getGameDayNo(), c.getGameTimePeriod(), c.getGameTimeRevision(),
                    c.getGameTimeChangedStepId(), c.getGameTimeUpdatedAt(), c.getUpdatedAt());
        }
    }
}
