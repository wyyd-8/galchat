package com.me.galchat.service.impl;

import java.util.ArrayList;
import java.util.List;

public final class CocDamageRules {

    private static final String STUN = "眩晕";

    private CocDamageRules() {
    }

    public static DamageExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("伤害公式不能为空");
        }
        String normalized = expression.replaceAll("\\s+", "");
        List<String> hpTerms = new ArrayList<>();
        boolean stun = false;
        int depth = 0;
        int termStart = 0;
        for (int index = 0; index <= normalized.length(); index++) {
            boolean boundary = index == normalized.length()
                    || normalized.charAt(index) == '+' && depth == 0;
            if (boundary) {
                String term = normalized.substring(termStart, index);
                if (term.isEmpty()) {
                    throw new IllegalArgumentException("伤害公式包含空白加数");
                }
                if (STUN.equals(term)) {
                    if (stun) {
                        throw new IllegalArgumentException("伤害公式只能包含一个眩晕词条");
                    }
                    stun = true;
                } else {
                    if (term.contains(STUN)) {
                        throw new IllegalArgumentException("眩晕必须作为独立加数使用");
                    }
                    hpTerms.add(term);
                }
                termStart = index + 1;
                continue;
            }
            char current = normalized.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("伤害公式括号不匹配");
                }
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("伤害公式括号不匹配");
        }
        if (!stun && hpTerms.isEmpty()) {
            throw new IllegalArgumentException("伤害公式不能为空");
        }
        return new DamageExpression(
                hpTerms.isEmpty() ? null : String.join("+", hpTerms),
                stun);
    }

    public record DamageExpression(String hpFormula, boolean stun) {
    }
}
