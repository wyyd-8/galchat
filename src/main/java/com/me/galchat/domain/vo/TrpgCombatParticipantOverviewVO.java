package com.me.galchat.domain.vo;

import java.util.List;

public record TrpgCombatParticipantOverviewVO(
        Long characterId,
        String name,
        boolean investigator,
        List<String> statuses,
        Integer hpCurrent,
        Integer hpMax,
        Integer armor,
        Integer dex,
        Integer build,
        Integer mov,
        String damageBonus) {
}
