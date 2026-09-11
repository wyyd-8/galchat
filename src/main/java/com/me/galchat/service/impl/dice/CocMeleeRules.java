package com.me.galchat.service.impl.dice;

import com.me.galchat.constant.MeleeDefenseMode;
import com.me.galchat.utils.DiceUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CocMeleeRules {

    private static final Pattern DICE = Pattern.compile("(?i)\\d+D\\d+");

    private CocMeleeRules() {
    }

    public static Winner winner(
            CocDiceRules.CheckRank attacker,
            CocDiceRules.CheckRank defender,
            MeleeDefenseMode defenseMode) {
        Objects.requireNonNull(attacker, "攻击检定等级不能为空");
        Objects.requireNonNull(defenseMode, "近战防守方式不能为空");
        if (defenseMode == MeleeDefenseMode.NONE) {
            return successful(attacker) ? Winner.ATTACKER : Winner.NONE;
        }
        Objects.requireNonNull(defender, "防守检定等级不能为空");
        if (!successful(attacker) && !successful(defender)) {
            return Winner.NONE;
        }
        int comparison = Integer.compare(
                attacker.strength(), defender.strength());
        if (comparison > 0) {
            return Winner.ATTACKER;
        }
        if (comparison < 0) {
            return Winner.DEFENDER;
        }
        return defenseMode == MeleeDefenseMode.DODGE
                ? Winner.DEFENDER : Winner.ATTACKER;
    }

    public static DamagePlan damagePlan(
            String weaponDamage,
            String damageBonus,
            boolean extreme,
            boolean canImpale) {
        CocDamageRules.DamageExpression parsed =
                normalizeWeaponDamage(weaponDamage);
        String weapon = parsed.hpFormula();
        if (weapon == null) {
            return new DamagePlan(null, false, false, true);
        }
        String bonus = normalizeDamageBonus(damageBonus);
        String ordinary = substituteDamageBonus(weapon, bonus);
        DiceUtils.prepare(ordinary);
        if (!extreme) {
            return new DamagePlan(ordinary, false, false, parsed.stun());
        }
        int maximum = maximumDamage(ordinary);
        if (!canImpale) {
            return new DamagePlan(
                    Integer.toString(maximum), true, false, parsed.stun());
        }
        List<String> extraDice = new ArrayList<>();
        Matcher matcher = DICE.matcher(weapon);
        while (matcher.find()) {
            extraDice.add(matcher.group());
        }
        String formula = extraDice.isEmpty()
                ? Integer.toString(maximum)
                : maximum + "+(" + String.join("+", extraDice) + ")";
        DiceUtils.prepare(formula);
        return new DamagePlan(formula, true, true, parsed.stun());
    }

    private static boolean successful(CocDiceRules.CheckRank rank) {
        return rank == CocDiceRules.CheckRank.REGULAR
                || rank == CocDiceRules.CheckRank.HARD
                || rank == CocDiceRules.CheckRank.EXTREME
                || rank == CocDiceRules.CheckRank.CRITICAL;
    }

    private static CocDamageRules.DamageExpression normalizeWeaponDamage(
            String damage) {
        if (damage == null || damage.isBlank()) {
            throw new IllegalArgumentException("近战武器伤害公式不能为空");
        }
        String normalized = damage.replaceAll("\\s+", "");
        if (normalized.contains("；") || normalized.contains(";")) {
            throw new IllegalArgumentException("近战武器伤害公式不能包含多档伤害");
        }
        return CocDamageRules.parse(normalized);
    }

    private static String normalizeDamageBonus(String damageBonus) {
        if (damageBonus == null || damageBonus.isBlank()) {
            throw new IllegalArgumentException("角色伤害加值不能为空");
        }
        String normalized = damageBonus.replaceAll("\\s+", "");
        if (normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        DiceUtils.prepare(normalized);
        return normalized;
    }

    private static String substituteDamageBonus(
            String weapon, String damageBonus) {
        return weapon.replace("半DB", "(" + damageBonus + ")/2")
                .replace("DB", "(" + damageBonus + ")");
    }

    private static int maximumDamage(String formula) {
        Matcher matcher = DICE.matcher(formula);
        StringBuilder maximum = new StringBuilder();
        while (matcher.find()) {
            String[] parts = matcher.group().toUpperCase().split("D", 2);
            int value = Math.multiplyExact(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]));
            matcher.appendReplacement(maximum, Integer.toString(value));
        }
        matcher.appendTail(maximum);
        return Math.max(0, DiceUtils.roll(maximum.toString()).getResult());
    }

    public enum Winner {
        ATTACKER,
        DEFENDER,
        NONE
    }

    public record DamagePlan(
            String formula,
            boolean maximumDamage,
            boolean impaling,
            boolean stun) {

        public DamagePlan(
                String formula,
                boolean maximumDamage,
                boolean impaling) {
            this(formula, maximumDamage, impaling, false);
        }
    }
}
