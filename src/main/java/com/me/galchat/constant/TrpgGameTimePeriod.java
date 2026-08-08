package com.me.galchat.constant;

import com.me.galchat.exception.UserRequestException;

import java.util.Locale;

public enum TrpgGameTimePeriod {
    DAWN("清晨"),
    MORNING("上午"),
    NOON("中午"),
    AFTERNOON("下午"),
    EVENING("晚上"),
    LATE_NIGHT("深夜");

    private final String label;

    TrpgGameTimePeriod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static TrpgGameTimePeriod parse(String value) {
        if (value == null || value.isBlank()) {
            throw new UserRequestException("游戏时段不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new UserRequestException(
                    "游戏时段仅支持DAWN、MORNING、NOON、AFTERNOON、EVENING或LATE_NIGHT");
        }
    }
}
