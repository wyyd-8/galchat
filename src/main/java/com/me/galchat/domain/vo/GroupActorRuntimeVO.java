package com.me.galchat.domain.vo;

public record GroupActorRuntimeVO(
        String actorType,
        Long actorId,
        String controlMode,
        Long modelApiId,
        String modelApiName,
        boolean modelApiAvailable) {
}
