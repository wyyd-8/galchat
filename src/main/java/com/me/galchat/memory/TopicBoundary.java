package com.me.galchat.memory;

import java.util.List;

public record TopicBoundary(List<Long> startIds, Long lastCheckedMessageId) {

    public TopicBoundary {
        startIds = startIds == null ? List.of() : List.copyOf(startIds);
    }

    public Long currentStartId() {
        return startIds.isEmpty() ? null : startIds.getLast();
    }
}
