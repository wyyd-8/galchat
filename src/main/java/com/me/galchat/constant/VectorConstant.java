package com.me.galchat.constant;

public final class VectorConstant {

    public static final int RERANK_TOP_N = 5;
    public static final int WORLD_EVENT_TOP_K = 5;
    public static final double WORLD_EVENT_DISTANCE_THRESHOLD = 0.27;

    public static final String SOURCE_METADATA_KEY = "source";
    public static final String TIMESTAMP_METADATA_KEY = "timestamp";
    public static final String USER_WORLD_ID_METADATA_KEY = "userWorldId";
    public static final String WORLD_ID_METADATA_KEY = "worldId";
    public static final String CHARACTER_ID_METADATA_KEY = "characterId";
    public static final String VISIBLE_CHARACTERS_METADATA_KEY = "visibleCharacters";
    public static final String TITLE_METADATA_KEY = "title";
    public static final String UNKNOWN_TIMESTAMP = "未知";
    public static final String WORLD_DETAIL_SOURCE = "world_detail";
    public static final String CHAT_HISTORY_SOURCE = "chat_history";
    public static final String WORLD_EVENT_SOURCE = "world_event";
    public static final String DOCUMENT_SEPARATOR = "\n---\n";
    public static final String CHAT_HISTORY_ID_PREFIX = "chat-history";
    public static final String WORLD_DETAIL_ID_PREFIX = "world-detail";
    public static final String WORLD_EVENT_ID_PREFIX = "world-event";

    private VectorConstant() {
    }
}
