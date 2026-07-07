package com.me.galchat.utils;

import com.me.galchat.domain.vo.DiceRollResultVO;
import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiceUtilsTest {

    @Test
    void rollsEveryDieAndUsesArithmeticPrecedence() {
        DiceRollResultVO result = DiceUtils.roll("3D6 + 1D4 * 2", new SequenceRandom(0, 1, 2, 3));

        assertEquals(14, result.getResult());
        assertEquals(2, result.getModules().size());
        assertEquals("3D6", result.getModules().get(0).getExpression());
        assertEquals(3, result.getModules().get(0).getDiceCount());
        assertEquals(6, result.getModules().get(0).getDiceSides());
        assertEquals("NORMAL", result.getModules().get(0).getModifier());
        assertEquals(java.util.List.of(1, 2, 3), result.getModules().get(0).getDice().stream()
                .map(die -> die.getValue()).toList());
        assertEquals(6, result.getModules().get(0).getResult());
        assertEquals("1D4", result.getModules().get(1).getExpression());
        assertEquals(java.util.List.of(4), result.getModules().get(1).getDice().stream()
                .map(die -> die.getValue()).toList());
    }

    @Test
    void bonusPercentileDieUsesLowestCandidateWithSharedOnesDigit() {
        DiceRollResultVO result = DiceUtils.roll("1D100#", new SequenceRandom(4, 4, 2));

        assertEquals(24, result.getResult());
        assertEquals("ADVANTAGE", result.getModules().getFirst().getModifier());
        assertEquals(java.util.List.of(4, 4, 2), result.getModules().getFirst().getDice().stream()
                .map(die -> die.getValue()).toList());
        assertEquals(java.util.List.of("PERCENTILE_ONES", "PERCENTILE_TENS", "PERCENTILE_TENS"),
                result.getModules().getFirst().getDice().stream().map(die -> die.getRole()).toList());
        assertEquals(java.util.List.of(true, false, true), result.getModules().getFirst().getDice().stream()
                .map(die -> die.isSelected()).toList());
        assertEquals(24, result.getModules().getFirst().getResult());
    }

    @Test
    void normalPercentileRollReturnsOnesAndTensDice() {
        DiceRollResultVO result = DiceUtils.roll("1D100", new SequenceRandom(4, 2));

        assertEquals(24, result.getResult());
        assertEquals(1, result.getModules().getFirst().getDiceCount());
        assertEquals(100, result.getModules().getFirst().getDiceSides());
        assertEquals(java.util.List.of(4, 2), result.getModules().getFirst().getDice().stream()
                .map(die -> die.getValue()).toList());
        assertEquals(java.util.List.of("PERCENTILE_ONES", "PERCENTILE_TENS"),
                result.getModules().getFirst().getDice().stream().map(die -> die.getRole()).toList());
    }

    @Test
    void twoPenaltyPercentileDiceUseHighestCandidateAndTreatDoubleZeroAsHundred() {
        DiceRollResultVO result = DiceUtils.roll("1D100$$", new SequenceRandom(0, 0, 3, 7));

        assertEquals(100, result.getResult());
        assertEquals("DOUBLE_DISADVANTAGE", result.getModules().getFirst().getModifier());
        assertEquals(java.util.List.of(0, 0, 3, 7), result.getModules().getFirst().getDice().stream()
                .map(die -> die.getValue()).toList());
        assertEquals(java.util.List.of(true, true, false, false), result.getModules().getFirst().getDice().stream()
                .map(die -> die.isSelected()).toList());
    }

    @Test
    void rejectsBooleanOperators() {
        assertThrows(IllegalArgumentException.class,
                () -> DiceUtils.roll("1D6 && 1D6", new SequenceRandom(1, 5)));
        assertThrows(IllegalArgumentException.class,
                () -> DiceUtils.roll("1D6 || 1D6", new SequenceRandom(1, 5)));
    }

    @Test
    void rejectsPercentileModifierOnOtherDice() {
        assertThrows(IllegalArgumentException.class,
                () -> DiceUtils.roll("1D20#", new SequenceRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> DiceUtils.roll("1D100#$", new SequenceRandom()));
    }

    private static final class SequenceRandom implements RandomGenerator {
        private final int[] values;
        private int index;

        private SequenceRandom(int... values) {
            this.values = values;
        }

        @Override
        public long nextLong() {
            return values[index++];
        }

        @Override
        public int nextInt(int bound) {
            return Math.floorMod(values[index++], bound);
        }

        @Override
        public int nextInt(int origin, int bound) {
            return origin + Math.floorMod(values[index++], bound - origin);
        }
    }
}
