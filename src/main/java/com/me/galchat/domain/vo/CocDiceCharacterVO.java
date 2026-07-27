package com.me.galchat.domain.vo;

import java.util.Map;

public record CocDiceCharacterVO(
        Long cardId,
        Long participantId,
        String name,
        Map<String, Integer> checkValues,
        Integer hpCurrent,
        Integer hpMax,
        Integer sanCurrent,
        Integer sanMax,
        Integer con,
        Integer armor,
        Boolean majorWound,
        Boolean unconscious,
        Boolean dying,
        Boolean dead,
        Boolean temporaryInsanity,
        String temporaryInsanityPhase,
        Integer temporaryInsanityRemainingHours) {
}
