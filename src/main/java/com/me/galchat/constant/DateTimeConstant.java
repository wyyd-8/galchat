package com.me.galchat.constant;

import java.time.format.DateTimeFormatter;

public final class DateTimeConstant {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    private DateTimeConstant() {
    }
}
