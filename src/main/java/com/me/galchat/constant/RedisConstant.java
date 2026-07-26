package com.me.galchat.constant;

import java.time.Duration;

public final class RedisConstant {

    public static final String CHAT_KEY_PREFIX = "chat:";
    public static final String DELAY_QUEUE_NAME = "chat:delay:queue";
    public static final String REPLY_QUEUE_NAME = "chat:reply:queue";
    public static final String USER_EVENT_LOG_DELAY_QUEUE_NAME = "user:event:log:delay:queue";
    public static final String WORLD_USER_AUTH_KEY = "world:user:auth";
    public static final String USER_CHARACTER_FAVOR_VALUE_KEY = "user:character:favor";
    public static final String USER_CHARACTER_PROMPT_INFO_KEY_PREFIX = "user:character:prompt:";
    public static final String CHAT_MEMORY_STEP_KEY_PREFIX = "chat:memory:step:";
    public static final String TOPIC_BOUNDARY_KEY_PREFIX = "chat:topic:boundary:";
    public static final String SINGLE_CHAT_LOCK_PREFIX = "chat:single:conversation:lock:";
    public static final String GROUP_CONVERSATION_LOCK_PREFIX = "chat:group:conversation:lock:";
    public static final String GROUP_WORLD_MUTATION_LOCK_PREFIX = "chat:group:world:mutation:lock:";
    public static final String EMAIL_VERIFY_CODE_KEY_PREFIX = "user:email:verify:code:";
    public static final String EMAIL_VERIFY_COOLDOWN_KEY_PREFIX = "user:email:verify:cooldown:";
    public static final String EMAIL_VERIFY_ATTEMPT_KEY_PREFIX = "user:email:verify:attempt:";
    public static final String EMAIL_VERIFY_FREEZE_KEY_PREFIX = "user:email:verify:freeze:";
    public static final String FAVOR_VALUE_HASH_FIELD = "favorValue";
    public static final String USER_INFO_PROMPT_HASH_FIELD = "userInfoPrompt";

    public static final String TYPING_SUFFIX = ":typing";
    public static final String INPUT_SUFFIX = ":input";
    public static final String LAST_ASSISTANT_SUFFIX = ":last_assistant";

    public static final Duration CHAT_MEMORY_STEP_TTL = Duration.ofDays(1);
    public static final Duration LAST_ASSISTANT_TTL = Duration.ofHours(1);
    public static final Duration INPUT_STATE_TTL = Duration.ofHours(1);
    public static final Duration EMAIL_VERIFY_CODE_TTL = Duration.ofMinutes(5);
    public static final Duration EMAIL_VERIFY_COOLDOWN_TTL = Duration.ofMinutes(1);
    public static final Duration EMAIL_VERIFY_ATTEMPT_TTL = Duration.ofMinutes(1);
    public static final Duration EMAIL_VERIFY_FREEZE_TTL = Duration.ofMinutes(30);
    public static final int EMAIL_VERIFY_MAX_ATTEMPTS = 10;
    public static final long REDIS_SCAN_COUNT = 1_000L;

    private RedisConstant() {
    }
}
