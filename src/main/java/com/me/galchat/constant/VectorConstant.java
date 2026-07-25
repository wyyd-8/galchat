package com.me.galchat.constant;

public final class VectorConstant {

    public static final int RERANK_TOP_N = 5;
    public static final int WORLD_EVENT_TOP_K = 5;
    public static final int GROUP_TOPIC_TOP_K = 5;
    public static final int PRE_CHAT_WORLD_DETAIL_LIMIT = 2;
    public static final int PRE_CHAT_HISTORY_LIMIT = 1;
    public static final int PRE_CHAT_WORLD_EVENT_LIMIT = 1;
    public static final double WORLD_EVENT_DISTANCE_THRESHOLD = 0.5;
    public static final double GROUP_TOPIC_DISTANCE_THRESHOLD = 0.5;

    public static final String SOURCE_METADATA_KEY = "source";
    public static final String TIMESTAMP_METADATA_KEY = "timestamp";
    public static final String USER_WORLD_ID_METADATA_KEY = "userWorldId";
    public static final String WORLD_ID_METADATA_KEY = "worldId";
    public static final String CHARACTER_ID_METADATA_KEY = "characterId";
    public static final String CONVERSATION_ID_METADATA_KEY = "conversationId";
    public static final String GROUP_TOPIC_ID_METADATA_KEY = "groupTopicId";
    public static final String START_SEQUENCE_METADATA_KEY = "startSequence";
    public static final String END_SEQUENCE_METADATA_KEY = "endSequence";
    public static final String START_MESSAGE_ID_METADATA_KEY = "startMessageId";
    public static final String END_MESSAGE_ID_METADATA_KEY = "endMessageId";
    public static final String WORLD_EVENT_LOG_ID_METADATA_KEY = "worldEventLogId";
    public static final String VISIBLE_CHARACTERS_METADATA_KEY = "visibleCharacters";
    public static final String TITLE_METADATA_KEY = "title";
    public static final String UNKNOWN_TIMESTAMP = "未知";
    public static final String WORLD_DETAIL_SOURCE = "world_detail";
    public static final String CHAT_HISTORY_SOURCE = "chat_history";
    public static final String GROUP_TOPIC_SOURCE = "group_topic";
    public static final String WORLD_EVENT_SOURCE = "world_event";
    public static final String DOCUMENT_SEPARATOR = "\n---\n";
    public static final String CHAT_HISTORY_ID_PREFIX = "chat-history";
    public static final String GROUP_TOPIC_ID_PREFIX = "group-topic";
    public static final String WORLD_DETAIL_ID_PREFIX = "world-detail";
    public static final String WORLD_EVENT_ID_PREFIX = "world-event";

    private VectorConstant() {
    }
}
