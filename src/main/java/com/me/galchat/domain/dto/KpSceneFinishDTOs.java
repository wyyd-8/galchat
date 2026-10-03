package com.me.galchat.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class KpSceneFinishDTOs {
    private KpSceneFinishDTOs() { }

    public record StepUndo(Long stepId, String errorBefore, LocalDateTime updatedBefore) { }

    public record FinishResult(String message, Long scenePlanId, Long turnId,
            boolean finishRequestedBefore, List<StepUndo> cancelledSteps) { }
}
