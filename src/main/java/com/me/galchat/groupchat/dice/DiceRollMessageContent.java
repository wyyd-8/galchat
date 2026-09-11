package com.me.galchat.groupchat.dice;

import java.util.List;

public record DiceRollMessageContent(Long summaryId, List<Integer> roundNos) {
}
