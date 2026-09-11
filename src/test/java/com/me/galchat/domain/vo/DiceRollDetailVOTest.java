package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.DiceRollResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiceRollDetailVOTest {

    @Test
    void pendingDetailExposesParticipantDisplayFieldsAndTargetWithoutInternalIds()
            throws Exception {
        DiceResolutionDataVO resolution = DiceResolutionDataVO.pending(
                "CHECK",
                null,
                Map.of(
                        "cardId", 77L,
                        "characterName", "康特·奈尔",
                        "checkName", "侦查",
                        "difficulty", "HARD",
                        "targetValue", 70));
        DiceRollResult entity = new DiceRollResult()
                .setId(1L)
                .setReason("追踪受伤足迹并观察周围环境")
                .setResolutionData(resolution);

        String json = JsonMapper.builder().build()
                .writeValueAsString(DiceRollDetailVO.from(entity));

        assertThat(json)
                .contains("\"characterName\":\"康特·奈尔\"")
                .contains("\"checkName\":\"侦查\"")
                .contains("\"difficulty\":\"HARD\"")
                .contains("\"targetValue\":35")
                .doesNotContain("cardId");
    }

    @Test
    void publicTargetValueReflectsTheEffectiveCheckRequirement() {
        assertThat(DiceResolutionDataVO.pending(
                "CHECK", null, Map.of("targetValue", 70, "difficulty", "REGULAR"))
                .publicView().getTargetValue()).isEqualTo(70);
        assertThat(DiceResolutionDataVO.pending(
                "CHECK", null, Map.of("targetValue", 70, "difficulty", "HARD"))
                .publicView().getTargetValue()).isEqualTo(35);
        assertThat(DiceResolutionDataVO.pending(
                "CHECK", null, Map.of("targetValue", 70, "difficulty", "EXTREME"))
                .publicView().getTargetValue()).isEqualTo(14);
        assertThat(DiceResolutionDataVO.pending(
                "FIREARM_ATTACK", null, Map.of("targetValue", 70, "difficultyIncrease", 3))
                .publicView().getTargetValue()).isEqualTo(1);
        assertThat(DiceResolutionDataVO.pending(
                "CHECK", null, Map.of("targetValue", 1, "difficulty", "EXTREME"))
                .publicView().getTargetValue()).isEqualTo(1);
    }

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

    @Test
    void detailExposesStructuredModifierFactorsWithoutExposingTheRuleSnapshot()
            throws Exception {
        DiceResolutionDataVO resolution = DiceResolutionDataVO.pending(
                "CHECK",
                null,
                Map.of(
                        "cardId", 77L,
                        "modifierFactors", List.of(
                                Map.of(
                                        "source", "KP",
                                        "kind", "BONUS",
                                        "diceCount", 1,
                                        "code", "KP_MODIFIER",
                                        "reason", "提前瞄准"),
                                Map.of(
                                        "source", "BACKEND",
                                        "kind", "PENALTY",
                                        "diceCount", 1,
                                        "code", "TARGET_IN_COVER",
                                        "reason", "目标处于掩护中"))));
        DiceRollResult entity = new DiceRollResult()
                .setId(1L)
                .setResolutionData(resolution);

        String json = JsonMapper.builder().build()
                .writeValueAsString(DiceRollDetailVO.from(entity));

        assertThat(json)
                .contains("\"modifierFactors\"")
                .contains("\"kind\":\"BONUS\"")
                .contains("\"reason\":\"提前瞄准\"")
                .contains("\"source\":\"BACKEND\"")
                .contains("\"diceCount\":1")
                .doesNotContain("cardId");
    }
}
