package com.me.galchat.domain.vo;

import com.me.galchat.constant.TrpgGameTimePeriod;
import com.me.galchat.domain.po.GroupConversation;

import java.time.LocalDateTime;

public record TrpgGameTimeVO(
        Integer dayNo,
        String period,
        String periodLabel,
        String displayText,
        Integer revision,
        LocalDateTime updatedAt) {

    public static TrpgGameTimeVO from(GroupConversation conversation) {
        if (conversation == null
                || conversation.getGameDayNo() == null
                || conversation.getGameTimePeriod() == null) {
            return null;
        }
        TrpgGameTimePeriod period = TrpgGameTimePeriod.parse(
                conversation.getGameTimePeriod());
        return new TrpgGameTimeVO(
                conversation.getGameDayNo(),
                period.name(),
                period.label(),
                "第" + chineseDay(conversation.getGameDayNo())
                        + "天 - " + period.label(),
                conversation.getGameTimeRevision() == null
                        ? 0 : conversation.getGameTimeRevision(),
                conversation.getGameTimeUpdatedAt());
    }

    private static String chineseDay(int dayNo) {
        if (dayNo <= 0 || dayNo >= 100) {
            return String.valueOf(dayNo);
        }
        String[] digits = {
                "零", "一", "二", "三", "四",
                "五", "六", "七", "八", "九"
        };
        if (dayNo < 10) {
            return digits[dayNo];
        }
        int tens = dayNo / 10;
        int ones = dayNo % 10;
        return (tens == 1 ? "" : digits[tens])
                + "十" + (ones == 0 ? "" : digits[ones]);
    }
}
