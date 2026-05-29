package com.me.galchat.constant;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

public final class UserEventLogConstant {

    public static final String TASK_TYPE_UPCOMING = "upcoming";
    public static final String TASK_TYPE_DAILY_CARE = "daily_care";

    public static final int HISTORY_LIMIT = 20;
    public static final int DISCUSSION_TOPIC_HISTORY_LIMIT = 50;

    public static final Duration MIN_CHAT_IDLE_TIME = Duration.ofMinutes(5);
    public static final Duration RETRY_DELAY = Duration.ofMinutes(10);
    public static final int MAX_RETRY_COUNT = 3;

    public static final List<LocalTime> SEARCH_TIMES = List.of(
            LocalTime.of(8, 0),
            LocalTime.of(13, 0),
            LocalTime.of(19, 0)
    );
    public static final String SCHEDULE_CRON = "0 0 8,13,19 * * *";
    public static final LocalTime DAILY_CARE_TIME = LocalTime.of(21, 0);
    public static final String DAILY_CARE_SCHEDULE_CRON = "0 0 21 * * *";
    public static final String SCHEDULE_ZONE = "Asia/Shanghai";

    public static final String EVENT_DESCRIPTION_JSON_KEY = "eventDescription";
    public static final String TIME_JSON_KEY = "time";
    public static final String DISCUSSION_TOPIC_JSON_KEY = "topic";
    public static final String DISCUSSION_MESSAGE_JSON_KEY = "message";

    private UserEventLogConstant() {
    }
}
