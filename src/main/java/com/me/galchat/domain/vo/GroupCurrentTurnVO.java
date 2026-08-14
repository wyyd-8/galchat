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
        boolean waitingForUser,
        Map<String, String> sceneOptions,
        List<GroupCurrentTurnStepVO> steps) {
}
