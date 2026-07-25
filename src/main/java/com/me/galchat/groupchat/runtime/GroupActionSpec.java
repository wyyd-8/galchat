package com.me.galchat.groupchat.runtime;

public record GroupActionSpec(
        String actionType,
        String actorType,
        Long actorId,
        Long planItemId,
        boolean completesPlanItem
) {
}
