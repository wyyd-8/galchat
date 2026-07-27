package com.me.galchat.constant;

public final class GroupChatConstant {

    private GroupChatConstant() {
    }

    public static final String MODE_CHAT = "chat";
    public static final String MODE_TRPG = "trpg";

    public static final String PLAN_SOURCE_USER = "USER";
    public static final String PLAN_SOURCE_SCENE = "SCENE";
    public static final String PLAN_SOURCE_COMBAT = "COMBAT";

    public static final String ACTOR_USER = "user";
    public static final String ACTOR_CHARACTER = "character";
    public static final String ACTOR_KP = "kp";
    public static final String ACTOR_NARRATOR = "narrator";

    public static final String MESSAGE_DIALOGUE = "dialogue";
    public static final String MESSAGE_NARRATION = "narration";

    public static final String ACTION_CHAT_REPLY = "chat_reply";
    public static final String ACTION_TRPG_SCENE = "trpg_scene_action";
    public static final String ACTION_TRPG_COMBAT = "trpg_combat_action";

    public static final String TOPIC_BOUNDARY_SEMANTIC = "semantic";
    public static final String TOPIC_BOUNDARY_CAPACITY = "capacity";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_CLOSED = "closed";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_STREAMING = "streaming";
    public static final String STATUS_WITHDRAWN = "withdrawn";

    public static final String EVENT_TURN_ACCEPTED = "turn.accepted";
    public static final String EVENT_REPLY_STARTED = "reply.started";
    public static final String EVENT_REASONING_DELTA = "reasoning.delta";
    public static final String EVENT_MESSAGE_DELTA = "message.delta";
    public static final String EVENT_MESSAGE_COMPLETED = "message.completed";
    public static final String EVENT_REPLY_FAILED = "reply.failed";
    public static final String EVENT_TURN_COMPLETED = "turn.completed";

    public static final int DEFAULT_HISTORY_PAGE_SIZE = 50;
    public static final int MAX_REPLY_STEPS = 12;
    public static final int MAX_GROUP_TOPIC_CHARS = 12000;
    public static final int MAX_CONSECUTIVE_WITHDRAW_COUNT = 3;
    public static final int CONTEXT_TOPIC_COUNT = 2;
}
