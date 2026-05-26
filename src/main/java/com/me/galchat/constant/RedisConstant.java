package com.me.galchat.constant;

import java.time.Duration;

public final class RedisConstant {

    public static final String CHAT_KEY_PREFIX = "chat:";
    public static final String DELAY_QUEUE_NAME = "chat:delay:queue";
    public static final String REPLY_QUEUE_NAME = "chat:reply:queue";
    public static final String USER_EVENT_LOG_DELAY_QUEUE_NAME = "user:event:log:delay:queue";
    public static final String WORLD_USER_AUTH_KEY = "world:user:auth";
    public static final String USER_CHARACTER_FAVOR_VALUE_KEY = "user:character:favor";
    public static final String CHAT_MEMORY_STEP_KEY_PREFIX = "chat:memory:step:";
    public static final String TOPIC_BOUNDARY_KEY_PREFIX = "chat:topic:boundary:";

    public static final String TYPING_SUFFIX = ":typing";
    public static final String INPUT_SUFFIX = ":input";
    public static final String LAST_ASSISTANT_SUFFIX = ":last_assistant";
    public static final String REPLY_LOCK_SUFFIX = ":reply_lock";

    public static final Duration CHAT_MEMORY_STEP_TTL = Duration.ofDays(1);
    public static final Duration LAST_ASSISTANT_TTL = Duration.ofHours(1);
    public static final Duration INPUT_STATE_TTL = Duration.ofHours(1);

    private RedisConstant() {
    }
}
