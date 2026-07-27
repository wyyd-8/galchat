package com.me.galchat.service;

import java.util.Set;

public interface DiceFollowUpLocator {
    Long requireLatestSummaryId(Long conversationId, Set<String> compatibleToolNames);
}
