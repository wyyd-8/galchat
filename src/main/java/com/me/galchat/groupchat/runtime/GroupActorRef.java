package com.me.galchat.groupchat.runtime;

import java.util.Objects;

public record GroupActorRef(String type, Long id) {

    public boolean matches(String otherType, Long otherId) {
        return Objects.equals(type, otherType) && Objects.equals(id, otherId);
    }
}
