package com.me.galchat.service.impl;

import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.FirearmFiringMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CocFirearmRulesTest {

    @Test
    void fullAutoSplitsBySkillAndEscalatesAfterTwoPenaltyDice() {
        assertThat(CocFirearmRules.groupSizes(
                FirearmFiringMode.FULL_AUTO, 47, 11))
                .containsExactly(4, 4, 3);

        assertThat(CocFirearmRules.adjustment(
                FirearmFiringMode.FULL_AUTO, 3,
                CocPercentileModifier.NORMAL))
                .isEqualTo(new CocFirearmRules.AttackAdjustment(
                        CocPercentileModifier.PENALTY_2, 1, false));
        assertThat(CocFirearmRules.adjustment(
                FirearmFiringMode.FULL_AUTO, 6,
                CocPercentileModifier.NORMAL).impossible()).isTrue();
    }

    @Test
    void handgunMultipleAndSemiAutoUseOneFixedPenaltyPerShot() {
        assertThat(CocFirearmRules.adjustment(
                FirearmFiringMode.HANDGUN_MULTIPLE, 2,
                CocPercentileModifier.BONUS_1))
                .isEqualTo(new CocFirearmRules.AttackAdjustment(
                        CocPercentileModifier.NORMAL, 0, false));
        assertThat(CocFirearmRules.adjustment(
                FirearmFiringMode.SEMI_AUTO, 4,
                CocPercentileModifier.NORMAL))
                .isEqualTo(new CocFirearmRules.AttackAdjustment(
                        CocPercentileModifier.PENALTY_1, 0, false));
    }

    @Test
    void extremeAutomaticHitBuildsNormalAndImpalingDamage() {
        CocFirearmRules.DamagePlan damage = CocFirearmRules.damagePlan(
                FirearmFiringMode.FULL_AUTO, 4, true,
                true, false, "1D10+2");

        assertThat(damage.hitCount()).isEqualTo(4);
        assertThat(damage.impalingHitCount()).isEqualTo(2);
        assertThat(damage.formula())
                .isEqualTo("(1D10+2)+(1D10+2)+(12+1D10+2)+(12+1D10+2)");
    }

    @Test
    void firearmDamageDoesNotCountStunDurationAsHpDamage() {
        CocFirearmRules.DamagePlan damage = CocFirearmRules.damagePlan(
                FirearmFiringMode.SINGLE, 1, false,
                false, false, "1D3+眩晕");

        assertThat(damage.formula()).isEqualTo("(1D3)");
    }

    @Test
    void fumbleOnlyBreaksAtMalfunctionThresholdWhenKpChoosesIt() {
        assertThat(CocFirearmRules.malfunction(
                98, true, 96, false))
                .isEqualTo(new CocFirearmRules.MalfunctionDecision(
                        false, true));
        assertThat(CocFirearmRules.malfunction(
                98, true, 96, true))
                .isEqualTo(new CocFirearmRules.MalfunctionDecision(
                        true, false));
        assertThat(CocFirearmRules.malfunction(
                97, false, 96, false))
                .isEqualTo(new CocFirearmRules.MalfunctionDecision(
                        true, false));
    }
}
