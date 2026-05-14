package com.me.galchat.memory;

public record TopicBoundary(Long previousStartId, Long currentStartId, Long lastCheckedMessageId) {

    public Long windowStartId() {
        if (previousStartId != null) {
            return previousStartId;
        }
        return currentStartId;
    }
}
