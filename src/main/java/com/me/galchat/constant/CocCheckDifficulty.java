package com.me.galchat.constant;

public enum CocCheckDifficulty {
    REGULAR(1),
    HARD(2),
    EXTREME(5);

    private final int divisor;

    CocCheckDifficulty(int divisor) {
        this.divisor = divisor;
    }

    public int requiredThreshold(int target) {
        return target / divisor;
    }
}
