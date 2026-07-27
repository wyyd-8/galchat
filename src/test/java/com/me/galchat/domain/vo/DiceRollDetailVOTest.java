package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.DiceRollResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiceRollDetailVOTest {

    @Test
    void detailExposesOutcomeButNotInternalRuleSnapshot() {
        DiceResolutionDataVO resolution = DiceResolutionDataVO.pending(
                "DAMAGE", 91L, Map.of("cardId", 77L, "characterName", "林恩"));
        resolution.setOutcome(Map.of("damage", 6));
        resolution.setEffect(Map.of("hpBefore", 10, "hpAfter", 4));
        DiceRollResult entity = new DiceRollResult()
                .setId(1L)
                .setResolutionData(resolution)
                .setResolvedAt(LocalDateTime.parse("2026-07-27T12:00:00"));

        DiceRollDetailVO detail = DiceRollDetailVO.from(entity);

        assertThat(detail.getResolution().getOutcome()).containsEntry("damage", 6);
        assertThat(detail.getResolution().getEffect()).containsEntry("hpAfter", 4);
        assertThat(detail.getResolution().getSourceResultId()).isEqualTo(91L);
        assertThat(DiceRollDetailVO.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("resolutionData");
    }
}
