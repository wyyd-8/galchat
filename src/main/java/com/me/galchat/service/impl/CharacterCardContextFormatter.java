package com.me.galchat.service.impl;

import com.me.galchat.constant.InsanityCatalog;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Component
public class CharacterCardContextFormatter {

    public String format(List<CocDiceCharacterVO> cards) {
        return format(cards, "investigator-cards", "investigator-card", true);
    }

    public String formatNpcs(List<CocDiceCharacterVO> cards) {
        return format(cards, "npc-cards", "npc-card", false);
    }

    private String format(
            List<CocDiceCharacterVO> cards,
            String containerName,
            String cardName,
            boolean includeParticipant) {
        if (cards == null || cards.isEmpty()) {
            return "<" + containerName + " />";
        }
        StringBuilder result = new StringBuilder("<")
                .append(containerName).append(">");
        for (CocDiceCharacterVO card : cards) {
            result.append("\n<").append(cardName).append(" name=\"")
                    .append(escape(card.name())).append('"');
            if (includeParticipant) {
                result.append(" participant-id=\"")
                        .append(card.participantId() == null
                                ? "player" : card.participantId())
                        .append('"');
            }
            result.append('>');
            result.append("\nHP：").append(value(card.hpCurrent())).append('/')
                    .append(value(card.hpMax()));
            result.append("；SAN：").append(value(card.sanCurrent())).append('/')
                    .append(value(card.sanMax()));
            result.append("；CON：").append(value(card.con()));
            result.append("；护甲：").append(value(card.armor()));
            appendCheckValues(result, card.checkValues());
            appendStatuses(result, card);
            result.append("\n</").append(cardName).append('>');
        }
        return result.append("\n</").append(containerName).append('>')
                .toString();
    }

    private void appendCheckValues(StringBuilder result, Map<String, Integer> checkValues) {
        if (checkValues == null || checkValues.isEmpty()) {
            return;
        }
        StringJoiner values = new StringJoiner("，");
        checkValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> values.add(entry.getKey() + "=" + entry.getValue()));
        result.append("\n检定值：").append(values);
    }

    private void appendStatuses(StringBuilder result, CocDiceCharacterVO card) {
        StringJoiner statuses = new StringJoiner("；");
        if (Boolean.TRUE.equals(card.majorWound())) {
            statuses.add("重伤");
        }
        if (Boolean.TRUE.equals(card.unconscious())) {
            statuses.add("昏迷");
        }
        if (Boolean.TRUE.equals(card.dying())) {
            statuses.add("濒死");
        }
        if (Boolean.TRUE.equals(card.dead())) {
            statuses.add("死亡");
        }
        if (Boolean.TRUE.equals(card.temporaryInsanity())) {
            statuses.add(insanityStatus(card));
        }
        if (statuses.length() > 0) {
            result.append("\n状态：").append(statuses);
        }
    }

    private String insanityStatus(CocDiceCharacterVO card) {
        String status;
        try {
            status = "临时疯狂：" + InsanityCatalog.display(card.temporaryInsanityPhase());
        } catch (IllegalArgumentException exception) {
            status = "临时疯狂（编号：" + card.temporaryInsanityPhase() + "）";
        }
        if (card.temporaryInsanityRemainingHours() != null) {
            status += "，剩余" + card.temporaryInsanityRemainingHours() + "小时";
        }
        return status;
    }

    private String value(Integer value) {
        return value == null ? "未知" : value.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
