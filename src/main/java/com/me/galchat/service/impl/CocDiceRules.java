package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.InsanityCatalog;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CocDiceRules {

    private CocDiceRules() {
    }

    public static CheckResolution resolveCheck(
            int roll, int target, CocCheckDifficulty difficulty) {
        requirePercentile(roll, "检定骰");
        requireTarget(target);
        Objects.requireNonNull(difficulty, "检定难度不能为空");

        int requiredThreshold = difficulty.requiredThreshold(target);
        CheckRank rank = rank(roll, target);
        CocCheckOutcome outcome;
        if (roll == 1) {
            outcome = CocCheckOutcome.CRITICAL_SUCCESS;
        } else if (isFumble(roll, requiredThreshold)) {
            outcome = CocCheckOutcome.FUMBLE;
        } else if (roll <= requiredThreshold) {
            outcome = CocCheckOutcome.SUCCESS;
        } else {
            outcome = CocCheckOutcome.FAILURE;
        }
        return new CheckResolution(outcome, rank, roll, target, requiredThreshold);
    }

    public static OpposedResolution resolveOpposed(
            List<OpposedCandidate> candidates, @Nullable String tieWinnerCharacterName) {
        if (candidates == null || candidates.size() < 2) {
            throw new IllegalArgumentException("对抗检定至少需要两个角色");
        }
        Set<String> names = new HashSet<>();
        List<RankedCandidate> ranked = candidates.stream().map(candidate -> {
            if (candidate == null || candidate.characterName() == null
                    || candidate.characterName().isBlank()) {
                throw new IllegalArgumentException("对抗角色名不能为空");
            }
            if (!names.add(candidate.characterName())) {
                throw new IllegalArgumentException("对抗角色不能重复");
            }
            requireTarget(candidate.target());
            requirePercentile(candidate.roll(), "对抗骰");
            return new RankedCandidate(candidate, rank(candidate.roll(), candidate.target()));
        }).sorted(Comparator
                .comparingInt((RankedCandidate candidate) -> candidate.rank().strength())
                .thenComparingInt(candidate -> candidate.candidate().target())
                .reversed()).toList();

        RankedCandidate first = ranked.getFirst();
        List<RankedCandidate> tied = ranked.stream()
                .filter(candidate -> candidate.rank() == first.rank())
                .filter(candidate -> candidate.candidate().target() == first.candidate().target())
                .toList();
        if (tied.size() == 1) {
            return new OpposedResolution(first.candidate().characterName(), false, first.rank());
        }
        if (tieWinnerCharacterName != null && !tieWinnerCharacterName.isBlank()) {
            return tied.stream()
                    .filter(candidate -> tieWinnerCharacterName.equals(
                            candidate.candidate().characterName()))
                    .findFirst()
                    .map(candidate -> new OpposedResolution(
                            candidate.candidate().characterName(), false, first.rank()))
                    .orElseThrow(() -> new IllegalArgumentException("平局胜者不属于实际平局角色"));
        }
        return new OpposedResolution(null, true, first.rank());
    }

    public static String selectSanLossFormula(
            CocCheckOutcome checkOutcome, String successFormula, String failureFormula) {
        Objects.requireNonNull(checkOutcome, "理智检定结果不能为空");
        return switch (checkOutcome) {
            case CRITICAL_SUCCESS, SUCCESS -> successFormula;
            case FAILURE, FUMBLE -> failureFormula;
        };
    }

    public static DamageResolution resolveDamage(int rolledDamage, int hpCurrent, int hpMax) {
        if (rolledDamage < 0) {
            throw new IllegalArgumentException("伤害不能为负数");
        }
        if (hpMax <= 0 || hpCurrent < 0 || hpCurrent > hpMax) {
            throw new IllegalArgumentException("生命值范围无效");
        }
        int hpAfter = Math.max(0, hpCurrent - rolledDamage);
        boolean majorWound = rolledDamage >= (hpMax + 1) / 2;
        return new DamageResolution(rolledDamage, hpCurrent, hpAfter, majorWound);
    }

    public static InsanityResolution resolveInsanity(
            int typeRoll, int durationRoll, @Nullable Integer detailRoll) {
        if (durationRoll < 1 || durationRoll > 10) {
            throw new IllegalArgumentException("疯狂持续时间骰必须为1到10");
        }
        String code = InsanityCatalog.code(typeRoll, detailRoll);
        return new InsanityResolution(
                typeRoll, durationRoll, detailRoll, code, InsanityCatalog.display(code));
    }

    private static CheckRank rank(int roll, int target) {
        if (roll == 1) {
            return CheckRank.CRITICAL;
        }
        if (isFumble(roll, target)) {
            return CheckRank.FUMBLE;
        }
        if (roll <= target / 5) {
            return CheckRank.EXTREME;
        }
        if (roll <= target / 2) {
            return CheckRank.HARD;
        }
        if (roll <= target) {
            return CheckRank.REGULAR;
        }
        return CheckRank.FAILURE;
    }

    private static boolean isFumble(int roll, int effectiveThreshold) {
        return effectiveThreshold < 50 ? roll >= 96 : roll == 100;
    }

    private static void requirePercentile(int roll, String label) {
        if (roll < 1 || roll > 100) {
            throw new IllegalArgumentException(label + "必须为1到100");
        }
    }

    private static void requireTarget(int target) {
        if (target < 1 || target > 100) {
            throw new IllegalArgumentException("检定目标值必须为1到100");
        }
    }

    public enum CheckRank {
        FUMBLE(0),
        FAILURE(1),
        REGULAR(2),
        HARD(3),
        EXTREME(4),
        CRITICAL(5);

        private final int strength;

        CheckRank(int strength) {
            this.strength = strength;
        }

        public int strength() {
            return strength;
        }
    }

    public record CheckResolution(
            CocCheckOutcome outcome,
            CheckRank rank,
            int roll,
            int target,
            int requiredThreshold) {
    }

    public record OpposedCandidate(String characterName, int target, int roll) {
    }

    public record OpposedResolution(String winner, boolean draw, CheckRank winningRank) {
    }

    public record DamageResolution(
            int damage,
            int hpBefore,
            int hpAfter,
            boolean majorWound) {
    }

    public record InsanityResolution(
            int typeRoll,
            int durationHours,
            Integer detailRoll,
            String code,
            String display) {
    }

    private record RankedCandidate(OpposedCandidate candidate, CheckRank rank) {
    }
}
