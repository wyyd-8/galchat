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

    static DiceRollResultVO roll(String formula, RandomGenerator random) {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("骰子公式不能为空");
        }
        if (formula.length() > MAX_FORMULA_LENGTH) {
            throw new IllegalArgumentException("骰子公式不能超过 " + MAX_FORMULA_LENGTH + " 个字符");
        }

        Parser parser = new Parser(formula, random);
        int result = Math.toIntExact(parser.parse());
        return new DiceRollResultVO(formula, parser.modules(), result);
    }

    private static final class Parser {
        private final String input;
        private final RandomGenerator random;
        private final List<DiceRollModuleVO> modules = new ArrayList<>();
        private int position;

        private Parser(String input, RandomGenerator random) {
            this.input = input;
            this.random = random;
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
                    left = Math.addExact(left, parseMultiplication());
                } else if (match("-")) {
                    left = Math.subtractExact(left, parseMultiplication());
                } else {
                    return left;
                }
            }
        }

        private long parseMultiplication() {
            long left = parseUnary();
            while (true) {
                if (match("*")) {
                    left = Math.multiplyExact(left, parseUnary());
                } else if (match("/")) {
                    left /= parseUnary();
                } else if (match("%")) {
                    left %= parseUnary();
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
                return Math.negateExact(parseUnary());
            }
            return parsePrimary();
        }

        private long parsePrimary() {
            if (match("(")) {
                long value = parseAddition();
                require(")");
                return value;
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
