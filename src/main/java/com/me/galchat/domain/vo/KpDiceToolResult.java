package com.me.galchat.domain.vo;

import java.util.List;

public record KpDiceToolResult(
        DiceRollSummaryVO summary,
        List<DiceRollDetailVO> results,
        String semanticResult) {
}
