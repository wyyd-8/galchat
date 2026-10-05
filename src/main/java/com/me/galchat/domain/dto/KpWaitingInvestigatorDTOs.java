package com.me.galchat.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class KpWaitingInvestigatorDTOs {
    private KpWaitingInvestigatorDTOs() {}

    public record Undo(Long itemId, Long characterId, String statusBefore,
                       LocalDateTime updatedBefore, boolean readyBefore) {}

    public record Result(String message, Long scenePlanId, List<Undo> undo) {}
}
