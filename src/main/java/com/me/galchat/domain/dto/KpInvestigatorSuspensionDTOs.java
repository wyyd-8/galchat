package com.me.galchat.domain.dto;

import java.util.List;

public final class KpInvestigatorSuspensionDTOs {
    private KpInvestigatorSuspensionDTOs() { }

    /** Stored with the tool result to undo only the suspensions created by this call. */
    public record SuspensionUndo(Long suspensionId, Long characterId, boolean readyBefore) { }

    public record SuspendResult(String message, Long scenePlanId, List<SuspensionUndo> undo) { }
}
