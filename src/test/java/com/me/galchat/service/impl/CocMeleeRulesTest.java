package com.me.galchat.service.impl;

import com.me.galchat.constant.MeleeDefenseMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CocMeleeRulesTest {

    @Test
    void dodgeWinsWhenBothChecksHaveTheSameSuccessRank() {
        assertThat(CocMeleeRules.winner(
                CocDiceRules.CheckRank.HARD,
                CocDiceRules.CheckRank.HARD,
                MeleeDefenseMode.DODGE))
                .isEqualTo(CocMeleeRules.Winner.DEFENDER);
    }

    @Test
    void attackerWinsCounterattackWhenBothChecksHaveTheSameSuccessRank() {
        assertThat(CocMeleeRules.winner(
                CocDiceRules.CheckRank.REGULAR,
                CocDiceRules.CheckRank.REGULAR,
                MeleeDefenseMode.COUNTERATTACK))
                .isEqualTo(CocMeleeRules.Winner.ATTACKER);
    }

    @Test
    void neitherSideWinsWhenBothOpposedChecksFail() {
        assertThat(CocMeleeRules.winner(
                CocDiceRules.CheckRank.FAILURE,
                CocDiceRules.CheckRank.FUMBLE,
                MeleeDefenseMode.COUNTERATTACK))
                .isEqualTo(CocMeleeRules.Winner.NONE);
    }

    @Test
    void undefendedTargetStillRequiresASuccessfulAttackCheck() {
        assertThat(CocMeleeRules.winner(
                CocDiceRules.CheckRank.FAILURE,
                null,
                MeleeDefenseMode.NONE))
                .isEqualTo(CocMeleeRules.Winner.NONE);
        assertThat(CocMeleeRules.winner(
                CocDiceRules.CheckRank.REGULAR,
                null,
                MeleeDefenseMode.NONE))
                .isEqualTo(CocMeleeRules.Winner.ATTACKER);
    }

    @Test
    void normalDamageExpandsDamageBonusHalfBonusAndTemporaryStunDice() {
        assertThat(CocMeleeRules.damagePlan(
                "1D3+眩晕+半DB", "+1D6", false, false))
                .isEqualTo(new CocMeleeRules.DamagePlan(
                        "1D3+1D6+(1D6)/2", false, false));
    }

    @Test
    void extremeImpaleAddsOnlyAnotherWeaponDamageRoll() {
        assertThat(CocMeleeRules.damagePlan(
                "1D4+2+DB", "+1D6", true, true))
                .isEqualTo(new CocMeleeRules.DamagePlan(
                        "12+(1D4)", true, true));
    }

    @Test
    void extremeBluntDamageUsesMaximumWeaponAndDamageBonus() {
        assertThat(CocMeleeRules.damagePlan(
                "1D8+DB", "-1", true, false))
                .isEqualTo(new CocMeleeRules.DamagePlan(
                        "7", true, false));
    }
}
