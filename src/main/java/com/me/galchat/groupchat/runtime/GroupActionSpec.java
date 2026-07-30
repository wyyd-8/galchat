package com.me.galchat.groupchat.runtime;

public record GroupActionSpec(
        String actionType,
        String actorType,
        Long actorId,
        Long subjectCharacterId,
        String groupKey,
        String groupName,
        Integer groupOrder,
        Integer itemOrder
) {
    public GroupActionSpec(
            String actionType,
            String actorType,
            Long actorId,
            String groupKey,
            String groupName,
            Integer groupOrder,
            Integer itemOrder) {
        this(actionType, actorType, actorId, null, groupKey, groupName,
                groupOrder, itemOrder);
    }

    public GroupActorRef actor() {
        return new GroupActorRef(actorType, actorId);
    }
}
