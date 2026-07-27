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
