package com.me.galchat.service.impl;

import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CocDiceSummaryFormatterTest {

    private final CocDiceSummaryFormatter formatter = new CocDiceSummaryFormatter();

    @Test
    void rebuildsOnlyCompletedRoundsInNumericOrder() {
        DiceRollResult first = check(1L, 1, 1, "林恩", "SUCCESS", 42);
        DiceRollResult incomplete = check(2L, 2, 1, "林恩", null, null);
        DiceRollResult third = check(3L, 3, 1, "林恩", "FUMBLE", 100);

        assertThat(formatter.rebuildTotalResult(List.of(third, incomplete, first)))
                .isEqualTo("林恩成功\n林恩大失败");
    }

    @Test
    void opposedRoundReturnsWinnerInsteadOfInternalSuccessRanks() {
        DiceRollResult lynn = opposed(1L, 1, "林恩", 70, 35, "林恩");
        DiceRollResult chen = opposed(2L, 2, "陈默", 45, 40, "林恩");

        assertThat(formatter.formatRound(List.of(lynn, chen)))
                .isEqualTo("林恩获胜")
                .doesNotContain("困难", "极难");
    }

    @Test
    void opposedRoundReturnsDrawWhenNoTieWinnerWasDeclared() {
        DiceRollResult lynn = opposed(1L, 1, "林恩", 60, 30, null);
        DiceRollResult chen = opposed(2L, 2, "陈默", 60, 30, null);

        assertThat(formatter.formatRound(List.of(lynn, chen))).isEqualTo("平局");
    }

    @Test
    void completedInsanityPairReplacesItsStandaloneSanLossWithCombinedText() {
        DiceRollResult sanLoss = new DiceRollResult()
                .setId(21L)
                .setRoundNo(2)
                .setDisplayOrder(1)
                .setResultData(new DiceRollResultVO("1D6", List.of(), 6))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "SAN_LOSS",
                        11L,
                        Map.of("characterName", "林恩", "cardId", 101L, "runId", 5L))
                        .setOutcome(Map.of("characterName", "林恩", "sanLoss", 6))
                        .setEffect(Map.of("sanBefore", 60, "sanAfter", 54, "sanLoss", 6)))
                .setResolvedAt(LocalDateTime.now());
        DiceRollResult type = new DiceRollResult()
                .setId(31L)
                .setRoundNo(3)
                .setDisplayOrder(1)
                .setResultData(new DiceRollResultVO("1D10", List.of(), 9))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "TEMPORARY_INSANITY_TYPE",
                        21L,
                        Map.of(
                                "characterName", "林恩",
                                "cardId", 101L,
                                "runId", 5L,
                                "sanLoss", 6))
                        .setOutcome(Map.of(
                                "characterName", "林恩",
                                "typeRoll", 9,
                                "detailRoll", 37,
                                "code", "9:037",
                                "display", "恐惧症（昆虫恐惧症：害怕昆虫）")))
                .setResolvedAt(LocalDateTime.now());
        DiceRollResult duration = new DiceRollResult()
                .setId(32L)
                .setRoundNo(3)
                .setDisplayOrder(2)
                .setResultData(new DiceRollResultVO("1D10", List.of(), 4))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "TEMPORARY_INSANITY_DURATION",
                        21L,
                        Map.of(
                                "characterName", "林恩",
                                "cardId", 101L,
                                "runId", 5L,
                                "sanLoss", 6))
                        .setOutcome(Map.of("characterName", "林恩", "durationHours", 4))
                        .setEffect(Map.of(
                                "temporaryInsanity", true,
                                "phase", "9:037",
                                "durationHours", 4)))
                .setResolvedAt(LocalDateTime.now());

        assertThat(formatter.rebuildTotalResult(List.of(sanLoss, type, duration)))
                .isEqualTo("林恩理智-6；进入临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫），持续4小时");
    }

    @Test
    void completedMajorWoundConReplacesDamageWithCombinedText() {
        DiceRollResult damage = new DiceRollResult()
                .setId(51L)
                .setRoundNo(1)
                .setDisplayOrder(1)
                .setResultData(new DiceRollResultVO("1D6", List.of(), 6))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "DAMAGE",
                        null,
                        Map.of(
                                "runId", 5L,
                                "cardId", 101L,
                                "characterName", "林恩"))
                        .setOutcome(Map.of(
                                "characterName", "林恩",
                                "rawDamage", 6,
                                "majorWound", true))
                        .setEffect(Map.of(
                                "hpBefore", 10,
                                "hpAfter", 4,
                                "hpLoss", 6,
                                "majorWoundBefore", false,
                                "majorWound", true,
                                "majorWoundChanged", true,
                                "unconsciousBefore", false,
                                "unconscious", false)))
                .setResolvedAt(LocalDateTime.now());
        DiceRollResult con = new DiceRollResult()
                .setId(61L)
                .setRoundNo(2)
                .setDisplayOrder(1)
                .setResultData(new DiceRollResultVO("1D100", List.of(), 80))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "MAJOR_WOUND_CON",
                        51L,
                        Map.of(
                                "runId", 5L,
                                "cardId", 101L,
                                "characterName", "林恩",
                                "targetValue", 50,
                                "hpLoss", 6))
                        .setOutcome(Map.of(
                                "characterName", "林恩",
                                "category", "FAILURE"))
                        .setEffect(Map.of(
                                "unconsciousBefore", false,
                                "unconscious", true)))
                .setResolvedAt(LocalDateTime.now());

        assertThat(formatter.rebuildTotalResult(List.of(damage, con)))
                .isEqualTo("林恩生命-6；受到重伤；CON检定失败，陷入昏迷");
    }

    @Test
    void healingRoundReportsActualHpGain() {
        DiceRollResult healing = new DiceRollResult()
                .setId(71L)
                .setRoundNo(1)
                .setDisplayOrder(1)
                .setResultData(new DiceRollResultVO("1D6", List.of(), 6))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "HEALING",
                        null,
                        Map.of(
                                "runId", 5L,
                                "cardId", 101L,
                                "characterName", "林恩"))
                        .setOutcome(Map.of(
                                "characterName", "林恩",
                                "rawHealing", 6))
                        .setEffect(Map.of(
                                "hpBefore", 8,
                                "hpAfter", 10,
                                "hpGain", 2,
                                "dyingBefore", false,
                                "dying", false)))
                .setResolvedAt(LocalDateTime.now());

        assertThat(formatter.formatRound(List.of(healing)))
                .isEqualTo("林恩生命+2");
    }

    private DiceRollResult check(
            Long id, int round, int order, String name, String category, Integer roll) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("cardId", id + 100);
        rule.put("characterName", name);
        rule.put("checkName", "侦查");
        rule.put("targetValue", 70);
        rule.put("difficulty", "REGULAR");
        rule.put("modifier", "NORMAL");
        rule.put("pushed", false);
        DiceResolutionDataVO resolution = DiceResolutionDataVO.pending("CHECK", null, rule);
        if (category != null) {
            resolution.setOutcome(Map.of(
                    "characterName", name,
                    "checkName", "侦查",
                    "category", category));
        }
        return new DiceRollResult()
                .setId(id)
                .setRoundNo(round)
                .setDisplayOrder(order)
                .setResultData(new DiceRollResultVO("1D100", List.of(), roll))
                .setResolutionData(resolution)
                .setResolvedAt(category == null ? null : LocalDateTime.now());
    }

    private DiceRollResult opposed(
            Long id, int order, String name, int target, int roll, String tieWinner) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("cardId", id + 100);
        rule.put("characterName", name);
        rule.put("checkName", "格斗");
        rule.put("targetValue", target);
        rule.put("difficulty", "REGULAR");
        rule.put("modifier", "NORMAL");
        rule.put("pushed", false);
        rule.put("tieWinnerCharacterName", tieWinner);
        return new DiceRollResult()
                .setId(id)
                .setRoundNo(1)
                .setDisplayOrder(order)
                .setResultData(new DiceRollResultVO("1D100", List.of(), roll))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "OPPOSED_CHECK", null, rule)
                        .setOutcome(Map.of(
                                "characterName", name,
                                "category", "SUCCESS")))
                .setResolvedAt(LocalDateTime.now());
    }
}
