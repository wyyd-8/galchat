package com.me.galchat.service.impl;

import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.FirearmFiringMode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CocFirearmRules {

    private static final Pattern DAMAGE_TERM = Pattern.compile(
            "(?i)(\\d+)D(\\d+)|(\\d+)");

    private CocFirearmRules() {
    }

    public static List<Integer> groupSizes(
            FirearmFiringMode mode, int skillValue, int bullets) {
        if (mode == null || skillValue < 1 || skillValue > 100
                || bullets < 1) {
            throw new IllegalArgumentException("枪械分组参数无效");
        }
        if (mode == FirearmFiringMode.HANDGUN_MULTIPLE
                || mode == FirearmFiringMode.SEMI_AUTO) {
            return java.util.stream.IntStream.range(0, bullets)
                    .map(ignored -> 1).boxed().toList();
        }
        if (mode != FirearmFiringMode.FULL_AUTO) {
            return List.of(bullets);
        }
        int capacity = Math.max(3, skillValue / 10);
        List<Integer> groups = new ArrayList<>();
        for (int remaining = bullets; remaining > 0;) {
            int size = Math.min(capacity, remaining);
            groups.add(size);
            remaining -= size;
        }
        return List.copyOf(groups);
    }

    public static AttackAdjustment adjustment(
            FirearmFiringMode mode,
            int globalGroupIndex,
            CocPercentileModifier baseModifier) {
        return adjustment(mode, globalGroupIndex, baseModifier, 0);
    }

    public static AttackAdjustment adjustment(
            FirearmFiringMode mode,
            int globalGroupIndex,
            CocPercentileModifier baseModifier,
            int automaticSituationPenaltyDice) {
        if (mode == null || globalGroupIndex < 0) {
            throw new IllegalArgumentException("枪械检定组参数无效");
        }
        if (automaticSituationPenaltyDice < 0) {
            throw new IllegalArgumentException("枪械场景惩罚骰数量无效");
        }
        int netPenalty = modifierValue(baseModifier)
                + automaticSituationPenaltyDice;
        if (mode == FirearmFiringMode.HANDGUN_MULTIPLE
                || mode == FirearmFiringMode.SEMI_AUTO) {
            netPenalty += 1;
        } else if (mode == FirearmFiringMode.FULL_AUTO) {
            netPenalty += globalGroupIndex;
        }
        if (netPenalty > 5) {
            return new AttackAdjustment(
                    CocPercentileModifier.PENALTY_2,
                    netPenalty - 2, true);
        }
        int difficultyIncrease = Math.max(0, netPenalty - 2);
        return new AttackAdjustment(
                percentileModifier(netPenalty),
                difficultyIncrease,
                false);
    }

    public static DamagePlan damagePlan(
            FirearmFiringMode mode,
            int bulletsInGroup,
            boolean extreme,
            boolean canImpale,
            boolean extremeDifficulty,
            String damageFormula) {
        if (mode == null || bulletsInGroup < 1) {
            throw new IllegalArgumentException("枪械伤害参数无效");
        }
        CocDamageRules.DamageExpression parsed =
                requireDamageFormula(damageFormula);
        String normalized = parsed.hpFormula();
        boolean barrage = mode == FirearmFiringMode.FULL_AUTO
                || mode == FirearmFiringMode.SHORT_BURST;
        int hitCount = barrage
                ? extreme ? bulletsInGroup : Math.max(1, bulletsInGroup / 2)
                : 1;
        int impalingHits = normalized != null
                && extreme && canImpale && !extremeDifficulty
                ? barrage ? Math.max(1, bulletsInGroup / 2) : 1
                : 0;
        if (normalized == null) {
            return new DamagePlan(
                    hitCount, 0, null, List.of(), parsed.stun());
        }
        int maximum = maximumDamage(normalized);
        List<String> terms = new ArrayList<>(hitCount);
        for (int index = 0; index < hitCount - impalingHits; index++) {
            terms.add("(" + normalized + ")");
        }
        for (int index = 0; index < impalingHits; index++) {
            terms.add("(" + maximum + "+" + normalized + ")");
        }
        if (extreme && !canImpale && !barrage) {
            terms.clear();
            terms.add(Integer.toString(maximum));
        }
        return new DamagePlan(
                hitCount,
                impalingHits,
                String.join("+", terms),
                List.copyOf(terms),
                parsed.stun());
    }

    public static MalfunctionDecision malfunction(
            int roll,
            boolean fumble,
            int malfunctionThreshold,
            boolean fumbleBreaksWeapon) {
        if (roll < malfunctionThreshold) {
            return new MalfunctionDecision(false, fumble);
        }
        if (fumble && !fumbleBreaksWeapon) {
            return new MalfunctionDecision(false, true);
        }
        return new MalfunctionDecision(true, false);
    }

    static int maximumDamage(String formula) {
        String normalized = requireDamageFormula(formula).hpFormula();
        if (normalized == null) {
            return 0;
        }
        int total = 0;
        int position = 0;
        Matcher matcher = DAMAGE_TERM.matcher(normalized);
        while (matcher.find()) {
            if (matcher.start() != position) {
                throw new IllegalArgumentException("枪械伤害公式仅支持骰子和正整数相加");
            }
            if (matcher.group(1) != null) {
                total = Math.addExact(total, Math.multiplyExact(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))));
            } else {
                total = Math.addExact(total,
                        Integer.parseInt(matcher.group(3)));
            }
            position = matcher.end();
            if (position < normalized.length()
                    && normalized.charAt(position) == '+') {
                position++;
            }
        }
        if (position != normalized.length()) {
            throw new IllegalArgumentException("枪械伤害公式仅支持骰子和正整数相加");
        }
        return total;
    }

    private static CocDamageRules.DamageExpression requireDamageFormula(
            String formula) {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("枪械伤害公式不能为空");
        }
        String normalized = formula.replaceAll("\\s+", "");
        if (normalized.contains("；") || normalized.contains(";")) {
            throw new IllegalArgumentException("多档枪械伤害需要先在人物卡中确定当前使用的一档");
        }
        return CocDamageRules.parse(normalized);
    }

    private static int modifierValue(CocPercentileModifier modifier) {
        return switch (modifier == null
                ? CocPercentileModifier.NORMAL : modifier) {
            case BONUS_2 -> -2;
            case BONUS_1 -> -1;
            case NORMAL -> 0;
            case PENALTY_1 -> 1;
            case PENALTY_2 -> 2;
        };
    }

    private static CocPercentileModifier percentileModifier(int netPenalty) {
        if (netPenalty <= -2) {
            return CocPercentileModifier.BONUS_2;
        }
        if (netPenalty == -1) {
            return CocPercentileModifier.BONUS_1;
        }
        if (netPenalty == 0) {
            return CocPercentileModifier.NORMAL;
        }
        if (netPenalty == 1) {
            return CocPercentileModifier.PENALTY_1;
        }
        return CocPercentileModifier.PENALTY_2;
    }

    public record AttackAdjustment(
            CocPercentileModifier modifier,
            int difficultyIncrease,
            boolean impossible) {
    }

    public record DamagePlan(
            int hitCount,
            int impalingHitCount,
            String formula,
            List<String> hitFormulas,
            boolean stun) {

        public DamagePlan(
                int hitCount,
                int impalingHitCount,
                String formula,
                List<String> hitFormulas) {
            this(hitCount, impalingHitCount, formula, hitFormulas, false);
        }
    }

    public record MalfunctionDecision(
            boolean breaksWeapon,
            boolean unhandledFumble) {
    }
}
