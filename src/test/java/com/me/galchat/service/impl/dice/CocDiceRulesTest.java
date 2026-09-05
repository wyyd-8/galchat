package com.me.galchat.service.impl.dice;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.CocPercentileModifier;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CocDiceRulesTest {

    @Test
    void normalCheckCollapsesInternalDifficultyButPreservesCriticalAndFumble() {
        assertThat(CocDiceRules.resolveCheck(20, 60, CocCheckDifficulty.HARD).outcome())
                .isEqualTo(CocCheckOutcome.SUCCESS);
        assertThat(CocDiceRules.resolveCheck(40, 60, CocCheckDifficulty.HARD).outcome())
                .isEqualTo(CocCheckOutcome.FAILURE);
        assertThat(CocDiceRules.resolveCheck(1, 20, CocCheckDifficulty.EXTREME).outcome())
                .isEqualTo(CocCheckOutcome.CRITICAL_SUCCESS);
        assertThat(CocDiceRules.resolveCheck(96, 40, CocCheckDifficulty.REGULAR).outcome())
                .isEqualTo(CocCheckOutcome.FUMBLE);
    }

    @Test
    void opposedTieUsesHigherTargetThenDeclaredTieWinner() {
        var sameRank = List.of(
                new CocDiceRules.OpposedCandidate("林恩", 60, 20),
                new CocDiceRules.OpposedCandidate("陈默", 50, 20));
        assertThat(CocDiceRules.resolveOpposed(sameRank, null).winner()).isEqualTo("林恩");

        var exactTie = List.of(
                new CocDiceRules.OpposedCandidate("林恩", 60, 20),
                new CocDiceRules.OpposedCandidate("陈默", 60, 20));
        assertThat(CocDiceRules.resolveOpposed(exactTie, "陈默").winner()).isEqualTo("陈默");
        assertThat(CocDiceRules.resolveOpposed(exactTie, null).draw()).isTrue();
    }

    @Test
    void opposedCheckHasNoWinnerWhenEveryCandidateFails() {
        var allFailed = List.of(
                new CocDiceRules.OpposedCandidate("林恩", 60, 80),
                new CocDiceRules.OpposedCandidate("陈默", 40, 96));

        CocDiceRules.OpposedResolution resolution =
                CocDiceRules.resolveOpposed(allFailed, null);

        assertThat(resolution.winner()).isNull();
        assertThat(resolution.draw()).isFalse();
    }

    @Test
    void sanLossFormulaUsesOnlyCollapsedSuccessOrFailureBranch() {
        assertThat(CocDiceRules.selectSanLossFormula(
                CocCheckOutcome.CRITICAL_SUCCESS, "0", "1D6")).isEqualTo("0");
        assertThat(CocDiceRules.selectSanLossFormula(
                CocCheckOutcome.FUMBLE, "0", "1D6")).isEqualTo("1D6");
    }

    @Test
    void damageClampsHpAndDetectsSingleHitMajorWound() {
        CocDiceRules.DamageResolution resolution =
                CocDiceRules.resolveDamage(6, 10, 10);

        assertThat(resolution.hpAfter()).isEqualTo(4);
        assertThat(resolution.majorWound()).isTrue();
    }

    @Test
    void insanityRequiresDetailOnlyForPhobiasAndManias() {
        assertThat(CocDiceRules.resolveInsanity(9, 4, 37).code()).isEqualTo("9:037");
        assertThatThrownBy(() -> CocDiceRules.resolveInsanity(9, 4, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CocDiceRules.resolveInsanity(4, 4, 37))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentileModifiersExposeSupportedDiceFormulas() {
        assertThat(CocPercentileModifier.NORMAL.formula()).isEqualTo("1D100");
        assertThat(CocPercentileModifier.BONUS_2.formula()).isEqualTo("1D100##");
        assertThat(CocPercentileModifier.PENALTY_2.formula()).isEqualTo("1D100$$");
    }
}
