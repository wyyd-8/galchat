package com.me.galchat.service.impl.dice;

import com.me.galchat.exception.UserRequestException;
import com.me.galchat.utils.DiceUtils;
import java.util.Locale;

/** A SAN loss is one formula, or success/failure formulas separated by '/'. */
record SanLossExpression(String success, String failure) {
    static SanLossExpression parse(String input) {
        if (input == null || input.isBlank()) throw new UserRequestException("SAN损失表达式不能为空");
        String[] parts = input.replaceAll("\\s+", "").toUpperCase(Locale.ROOT).split("/", -1);
        if (parts.length < 1 || parts.length > 2) throw new UserRequestException("SAN损失格式应为公式或成功公式/失败公式");
        for (String part : parts) {
            if (!part.matches("(?:[0-9]+D[0-9]+|[0-9]+)(?:\\+(?:[0-9]+D[0-9]+|[0-9]+))*")) {
                throw new UserRequestException("SAN损失公式只支持非负整数、NdM和相加");
            }
            try { DiceUtils.prepare(part); bound(part, true); }
            catch (IllegalArgumentException | ArithmeticException e) { throw new UserRequestException("SAN损失公式不合法", e); }
        }
        return new SanLossExpression(parts[0], parts.length == 1 ? parts[0] : parts[1]);
    }
    boolean needsCheck() { return !success.equals(failure); }
    static int bound(String formula, boolean maximum) {
        int total = 0;
        for (String term : formula.toUpperCase(Locale.ROOT).split("\\+")) {
            String[] dice = term.split("D");
            int value = dice.length == 1 ? Integer.parseInt(term)
                    : Math.multiplyExact(Integer.parseInt(dice[0]), maximum ? Integer.parseInt(dice[1]) : 1);
            total = Math.addExact(total, value);
        }
        return total;
    }
}
