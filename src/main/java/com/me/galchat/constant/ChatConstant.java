package com.me.galchat.constant;

public final class ChatConstant {

    public static final String THINKING_TYPE = "thinking";
    public static final String TOOL_TYPE = "tool";
    public static final String RESPONSE_TYPE = "reponse";
    public static final String AUTO_SEARCH_INFO_TYPE = "auto_search_info";
    public static final String STORY_START_TYPE = "story_start";
    public static final String STORY_PROGRESS_TYPE = "story_progress";
    public static final String STORY_END_TYPE = "story_end";
    public static final String AUTO_SEARCH_INFO_PREFIX = "自动调用searchInfo结果：\n";
    public static final String TOPIC_CONVERSATION_INFO_CONTEXT_KEY = "topic_conversation_info";
    public static final String TOPIC_USER_MESSAGE_ID_CONTEXT_KEY = "topic_user_message_id";
    public static final String TOPIC_PREVIOUS_START_ID_KEY = "previousStartId";
    public static final String TOPIC_CURRENT_START_ID_KEY = "currentStartId";
    public static final String TOPIC_LAST_CHECKED_MESSAGE_ID_KEY = "lastCheckedMessageId";

    public static final int DEFAULT_HISTORY_PAGE_SIZE = 20;
    public static final int MAX_CONTEXT_LENGTH = 430000;
    public static final int MAX_TOPIC_CONVERSATION_LENGTH = 10000;

    private ChatConstant() {
    }
}
