package com.me.galchat.domain.vo;

import java.util.List;

public record DiceRollProgressVO(
        DiceRollSummaryVO summary,
        DiceRollDetailVO rolledResult,
        List<DiceRollDetailVO> createdResults) {
}
