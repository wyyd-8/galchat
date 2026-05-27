package com.me.galchat.constant;

import java.time.Duration;

public final class StoryConstant {

    public static final String ACTIVE_STATUS = "ACTIVE";
    public static final String CLOSED_STATUS = "CLOSED";

    public static final Duration STORY_LOCK_WAIT = Duration.ofSeconds(5);
    public static final Duration CHAT_LOCK_WAIT = Duration.ZERO;

    private StoryConstant() {
    }
}
