package com.me.galchat.constant;

public final class GroupChatConstant {
    public static final String TURN_SOURCE_SUMMARY = "summary";
    public static final String ACTION_TRPG_SUMMARY = "trpg_summary";
    public static final String ACTION_TRPG_RUN_SCENE_CLOSE = "trpg_run_scene_close";

    private GroupChatConstant() {
    }

    public static final String MODE_CHAT = "chat";
    public static final String MODE_TRPG = "trpg";

    public static final String PLAN_SOURCE_USER = "USER";
    public static final String PLAN_SOURCE_SCENE = "SCENE";
    public static final String PLAN_SOURCE_COMBAT = "COMBAT";
    public static final String PLAN_SOURCE_POST_COMBAT = "POST_COMBAT";
    public static final String TURN_SOURCE_SCENE_SELECTION = "SCENE_SELECTION";

    public static final String ACTOR_USER = "user";
    public static final String ACTOR_CHARACTER = "character";
    public static final String ACTOR_KP = "kp";
    public static final String ACTOR_NARRATOR = "narrator";

    public static final String CONTROL_MODEL = "MODEL";
    public static final String CONTROL_MANUAL = "MANUAL";

    public static final String MESSAGE_DIALOGUE = "dialogue";
    public static final String MESSAGE_NARRATION = "narration";
    public static final String MESSAGE_DICE_ROLL = "dice_roll";
    public static final String MESSAGE_MATERIAL = "material";
    public static final String MESSAGE_COMBAT_RESULT = "combat_result";
    public static final String MESSAGE_EPILOGUE = "epilogue";

    public static final String ACTION_CHAT_REPLY = "chat_reply";
    public static final String ACTION_TRPG_SCENE = "trpg_scene_action";
    public static final String ACTION_TRPG_SCENE_INTRO =
            "trpg_scene_intro";
    public static final String ACTION_TRPG_COMBAT = "trpg_combat_action";
    public static final String ACTION_COMBAT_INTRO = "combat_intro";
    public static final String ACTION_COMBAT_ATTACK = "combat_attack";
    public static final String ACTION_COMBAT_REACTION_ROUTE = "combat_reaction_route";
    public static final String ACTION_COMBAT_DEFENSE = "combat_defense";
    public static final String ACTION_COMBAT_ADJUDICATE = "combat_adjudicate";
    public static final String ACTION_COMBAT_UNCONSCIOUS_RECOVERY =
            "combat_unconscious_recovery";
    public static final String ACTION_TRPG_UNCONSCIOUS_RECOVERY =
            "trpg_unconscious_recovery";
    public static final String ACTION_TRPG_POST_COMBAT_TRANSITION =
            "trpg_post_combat_transition";
    public static final String ACTION_TRPG_INTERACTION_RESPONSE =
            "trpg_interaction_response";
    public static final String ACTION_TRPG_SCENE_SELECTION = "trpg_scene_selection";

    public static final String TOPIC_BOUNDARY_SEMANTIC = "semantic";
    public static final String TOPIC_BOUNDARY_CAPACITY = "capacity";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_CLOSED = "closed";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_BLOCKED = "blocked";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_STREAMING = "streaming";
    public static final String STATUS_WAITING_INPUT = "waiting_input";
    public static final String STATUS_WAITING_INTERACTION =
            "waiting_interaction";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_WAITING_DICE = "waiting_dice";
    public static final String STATUS_WITHDRAWN = "withdrawn";

    public static final String PARTICIPANT_ACTIVE = "ACTIVE";
    public static final String PARTICIPANT_WAITING = "WAITING";
    public static final String PARTICIPANT_READY = "READY";

    public static final String EVENT_TURN_ACCEPTED = "turn.accepted";
    public static final String EVENT_REPLY_STARTED = "reply.started";
    public static final String EVENT_REASONING_DELTA = "reasoning.delta";
    public static final String EVENT_DECISION_DELTA = "decision.delta";
    public static final String EVENT_DECISION_COMPLETED =
            "decision.completed";
    public static final String EVENT_MESSAGE_DELTA = "message.delta";
    public static final String EVENT_DICE_ROLL_CREATED = "dice_roll.created";
    public static final String EVENT_MATERIAL_CREATED = "material.created";
    public static final String EVENT_GAME_TIME_CHANGED =
            "game_time.changed";
    public static final String EVENT_SCENE_OPTIONS_CREATED =
            "scene_selection.options";
    public static final String EVENT_SCENE_CHOICE_CREATED =
            "scene_selection.choice";
    public static final String EVENT_MESSAGE_COMPLETED = "message.completed";
    public static final String EVENT_REPLY_FAILED = "reply.failed";
    public static final String EVENT_GENERATION_FAILED =
            "generation.failed";
    public static final String EVENT_TURN_COMPLETED = "turn.completed";
    public static final String EVENT_TURN_WAITING_INPUT = "turn.waiting_input";
    public static final String EVENT_TURN_PAUSED = "turn.paused";
    public static final String EVENT_STREAM_CAUGHT_UP = "stream.caught_up";
    public static final String EVENT_COMBAT_STARTED = "combat.started";
    public static final String EVENT_COMBAT_COMPLETED = "combat.completed";

    public static final String COMBAT_STATUS_START_REQUESTED = "START_REQUESTED";
    public static final String COMBAT_STATUS_ACTIVE = "ACTIVE";
    public static final String COMBAT_STATUS_COMPLETED = "COMPLETED";
    public static final String COMBAT_STATUS_CANCELLED = "CANCELLED";
    public static final String COMBAT_ORDER_DEX = "DEX";
    public static final String COMBAT_ORDER_INVESTIGATORS_FIRST = "INVESTIGATORS_FIRST";

    public static final int DEFAULT_HISTORY_PAGE_SIZE = 50;
    public static final int MAX_REPLY_STEPS = 12;
    public static final int MAX_GROUP_TOPIC_CHARS = 12000;
    public static final int MAX_CONSECUTIVE_WITHDRAW_COUNT = 3;
    public static final int CONTEXT_TOPIC_COUNT = 2;
}
