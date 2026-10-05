package com.me.galchat.domain.dto;

import java.util.List;

public record InvestigatorSceneFinishResult(
        String message, Long scenePlanId, Long turnId, Long characterId,
        boolean readyBefore, boolean finishRequestedBefore, boolean allReady,
        List<KpSceneFinishDTOs.StepUndo> cancelledSteps) { }
