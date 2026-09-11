package com.me.galchat.constant;

public enum CocPercentileModifier {
    NORMAL("1D100"),
    BONUS_1("1D100#"),
    BONUS_2("1D100##"),
    PENALTY_1("1D100$"),
    PENALTY_2("1D100$$");

    private final String formula;

    CocPercentileModifier(String formula) {
        this.formula = formula;
    }

    public String formula() {
        return formula;
    }
}
