package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;

import java.util.List;

public record DiceRollAggregate(
        DiceRollSummary summary,
        List<DiceRollResult> results) {
}
