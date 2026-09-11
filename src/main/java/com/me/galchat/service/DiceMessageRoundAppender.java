package com.me.galchat.service;

import java.util.Collection;

public interface DiceMessageRoundAppender {

    void appendRounds(
            Long conversationId, Long summaryId, Collection<Integer> roundNos);
}
