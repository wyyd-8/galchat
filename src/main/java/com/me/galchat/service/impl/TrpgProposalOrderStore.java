package com.me.galchat.service.impl;

import java.util.List;
import java.util.Optional;

public interface TrpgProposalOrderStore {

    Optional<State> load(Long conversationId);

    void save(Long conversationId, State state);

    void evict(Long conversationId);

    record State(Long cursorTurnId, List<String> actorKeys) {
        public State {
            actorKeys = actorKeys == null ? List.of()
                    : List.copyOf(actorKeys);
        }
    }
}
