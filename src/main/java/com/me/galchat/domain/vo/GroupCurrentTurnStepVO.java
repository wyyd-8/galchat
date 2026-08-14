package com.me.galchat.domain.vo;

public record GroupCurrentTurnStepVO(
        Long stepId,
        Integer itemOrder,
        String actorType,
        Long actorId,
        Long subjectCharacterId,
        String status,
        String error) {
}
