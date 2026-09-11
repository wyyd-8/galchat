package com.me.galchat.utils;

import com.me.galchat.domain.vo.DiceRollModuleVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.DiceRollValueVO;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class DiceUtils {

    private static final int MAX_FORMULA_LENGTH = 500;
    private static final int MAX_DICE_COUNT = 1_000;
    private static final int MAX_DIE_SIDES = 1_000_000;

    private DiceUtils() {
    }

    public static DiceRollResultVO roll(String formula) {
        return roll(formula, ThreadLocalRandom.current());
    }

    /**
     * Parses a dice formula into the same structure returned by {@link #roll(String)},
     * without generating random values. The returned dice and modules have null
     * result values and can be stored as a pending user-roll placeholder.
     */
    public static DiceRollResultVO prepare(String formula) {
        validateFormula(formula);
        Parser parser = new Parser(formula, null, false);
        parser.parse();
        return new DiceRollResultVO(formula, parser.modules(), null);
    }

    static DiceRollResultVO roll(String formula, RandomGenerator random) {
        validateFormula(formula);

        Parser parser = new Parser(formula, random, true);
        int result = Math.toIntExact(parser.parse());
        return new DiceRollResultVO(formula, parser.modules(), result);
    }

    private static void validateFormula(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("骰子公式不能为空");
        }
        if (formula.length() > MAX_FORMULA_LENGTH) {
            throw new IllegalArgumentException("骰子公式不能超过 " + MAX_FORMULA_LENGTH + " 个字符");
        }
    }

    private static final class Parser {
        private final String input;
        private final RandomGenerator random;
        private final boolean generateResults;
        private final List<DiceRollModuleVO> modules = new ArrayList<>();
        private int position;

        private Parser(String input, RandomGenerator random, boolean generateResults) {
            this.input = input;
            this.random = random;
            this.generateResults = generateResults;
        }

        private long parse() {
            long result = parseAddition();
            skipWhitespace();
            if (position != input.length()) {
                throw error("无法识别的内容");
            }
            return result;
        }

        private List<DiceRollModuleVO> modules() {
            return modules;
        }

        private long parseAddition() {
            long left = parseMultiplication();
            while (true) {
                if (match("+")) {
                    long right = parseMultiplication();
                    left = generateResults ? Math.addExact(left, right) : 1;
                } else if (match("-")) {
                    long right = parseMultiplication();
                    left = generateResults ? Math.subtractExact(left, right) : 1;
                } else {
                    return left;
                }
            }
        }

        private long parseMultiplication() {
            long left = parseUnary();
            while (true) {
                if (match("*")) {
                    long right = parseUnary();
                    left = generateResults ? Math.multiplyExact(left, right) : 1;
                } else if (match("/")) {
                    long right = parseUnary();
                    left = generateResults ? left / right : 1;
                } else if (match("%")) {
                    long right = parseUnary();
                    left = generateResults ? left % right : 1;
                } else {
                    return left;
                }
            }
        }

        private long parseUnary() {
            if (match("+")) {
                return parseUnary();
            }
            if (match("-")) {
                long value = parseUnary();
                return generateResults ? Math.negateExact(value) : 1;
            }
            return parsePrimary();
        }

        private long parsePrimary() {
            if (match("(")) {
                long value = parseAddition();
                require(")");
                return value;
            }

            if (match("max")) {
                require("(");
                long first = parseAddition();
                require(",");
                long second = parseAddition();
                require(")");
                return generateResults ? Math.max(first, second) : 1;
            }

            skipWhitespace();
            int start = position;
            long number = parseUnsignedInteger();
            skipWhitespace();
            if (position < input.length() && (input.charAt(position) == 'D' || input.charAt(position) == 'd')) {
                position++;
                long sides = parseUnsignedInteger();
                return rollDice(start, number, sides);
            }
            return number;
        }

        private long rollDice(int start, long countValue, long sidesValue) {
            if (countValue < 1 || countValue > MAX_DICE_COUNT) {
                throw error("骰子数量必须在 1 到 " + MAX_DICE_COUNT + " 之间");
            }
            if (sidesValue < 1 || sidesValue > MAX_DIE_SIDES) {
                throw error("骰子面数必须在 1 到 " + MAX_DIE_SIDES + " 之间");
            }

            int modifier = parsePercentileModifier();
            if (modifier != 0 && (countValue != 1 || sidesValue != 100)) {
                throw error("奖励骰或惩罚骰只能用于 1D100");
            }

            if (!generateResults) {
                return prepareDice(start, countValue, sidesValue, modifier);
            }

            List<DiceRollValueVO> dice = new ArrayList<>();
            int selected;
            if (countValue == 1 && sidesValue == 100) {
                selected = rollPercentile(modifier, dice);
            } else {
                long total = 0;
                for (int i = 0; i < (int) countValue; i++) {
                    int value = random.nextInt(1, (int) sidesValue + 1);
                    dice.add(new DiceRollValueVO((int) sidesValue, value, "NORMAL", true));
                    total = Math.addExact(total, value);
                }
                selected = Math.toIntExact(total);
            }

            String expression = input.substring(start, position).replaceAll("\\s+", "");
            modules.add(new DiceRollModuleVO(
                    expression,
                    (int) countValue,
                    (int) sidesValue,
                    modifierName(modifier),
                    dice,
                    selected
            ));
            return selected;
        }

        private long prepareDice(int start, long countValue, long sidesValue, int modifier) {
            List<DiceRollValueVO> dice = new ArrayList<>();
            if (countValue == 1 && sidesValue == 100) {
                dice.add(new DiceRollValueVO(10, null, "PERCENTILE_ONES", true));
                for (int i = 0; i <= Math.abs(modifier); i++) {
                    dice.add(new DiceRollValueVO(
                            10,
                            null,
                            "PERCENTILE_TENS",
                            modifier == 0
                    ));
                }
            } else {
                for (int i = 0; i < (int) countValue; i++) {
                    dice.add(new DiceRollValueVO((int) sidesValue, null, "NORMAL", true));
                }
            }

            String expression = input.substring(start, position).replaceAll("\\s+", "");
            modules.add(new DiceRollModuleVO(
                    expression,
                    (int) countValue,
                    (int) sidesValue,
                    modifierName(modifier),
                    dice,
                    null
            ));

            // Parsing arithmetic still needs a temporary value. One per die is
            // always within the valid range and is discarded from the response.
            return countValue;
        }

        private int parsePercentileModifier() {
            skipWhitespace();
            if (position >= input.length() || (input.charAt(position) != '#' && input.charAt(position) != '$')) {
                return 0;
            }
            char symbol = input.charAt(position++);
            int count = 1;
            if (position < input.length() && input.charAt(position) == symbol) {
                position++;
                count++;
            }
            if (position < input.length() && (input.charAt(position) == '#' || input.charAt(position) == '$')) {
                throw error("奖励骰或惩罚骰最多只能有两个，且不能混用");
            }
            return symbol == '#' ? count : -count;
        }

        private int rollPercentile(int modifier, List<DiceRollValueVO> dice) {
            int ones = random.nextInt(10);
            List<Integer> tensValues = new ArrayList<>();
            int selected = 0;
            int selectedIndex = 0;
            for (int i = 0; i <= Math.abs(modifier); i++) {
                int tens = random.nextInt(10);
                tensValues.add(tens);
                int value = tens == 0 && ones == 0 ? 100 : tens * 10 + ones;
                if (i == 0
                        || (modifier > 0 && value < selected)
                        || (modifier < 0 && value > selected)) {
                    selected = value;
                    selectedIndex = i;
                }
            }

            dice.add(new DiceRollValueVO(10, ones, "PERCENTILE_ONES", true));
            for (int i = 0; i < tensValues.size(); i++) {
                dice.add(new DiceRollValueVO(10, tensValues.get(i), "PERCENTILE_TENS", i == selectedIndex));
            }
            return selected;
        }

        private String modifierName(int modifier) {
            return switch (modifier) {
                case 1 -> "ADVANTAGE";
                case 2 -> "DOUBLE_ADVANTAGE";
                case -1 -> "DISADVANTAGE";
                case -2 -> "DOUBLE_DISADVANTAGE";
                default -> "NORMAL";
            };
        }

        private long parseUnsignedInteger() {
            skipWhitespace();
            int start = position;
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("此处需要整数或骰子");
            }
            try {
                return Long.parseLong(input.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("整数过大");
            }
        }

        private boolean match(String token) {
            skipWhitespace();
            if (!input.startsWith(token, position)) {
                return false;
            }
            position += token.length();
            return true;
        }

        private void require(String token) {
            if (!match(token)) {
                throw error("缺少 '" + token + "'");
            }
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "（位置 " + position + "）");
        }
    }
}
