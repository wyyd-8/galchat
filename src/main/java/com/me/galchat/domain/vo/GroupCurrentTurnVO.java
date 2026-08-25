package com.me.galchat.domain.vo;

import java.util.List;
import java.util.Map;

public record GroupCurrentTurnVO(
        Long turnId,
        Long planId,
        String planSource,
        Long planContextId,
        String status,
        Long stepId,
        String actionType,
        Integer itemOrder,
        String inputType,
        String sceneName,
        Long promptMessageId,
        String interactionType,
        Integer interactionSeq,
        boolean waitingForUser,
        Map<String, String> sceneOptions,
        GroupRouteContextVO routeContext,
        List<GroupCurrentTurnStepVO> steps) {
}
