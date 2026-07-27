package com.me.galchat.groupchat.runtime;

public record GroupActionSpec(
        String actionType,
        String actorType,
        Long actorId,
        String groupKey,
        String groupName,
        Integer groupOrder,
        Integer itemOrder
) {
    public GroupActorRef actor() {
        return new GroupActorRef(actorType, actorId);
    }
}
