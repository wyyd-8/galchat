package com.me.galchat.domain.dto;

import java.util.List;
import java.time.LocalDateTime;
import com.me.galchat.domain.po.TrpgInvestigatorSuspension;

public final class KpInvestigatorSuspensionDTOs {
    private KpInvestigatorSuspensionDTOs() { }

    /** Stored with the tool result to undo only the suspensions created by this call. */
    public record SuspensionUndo(Long suspensionId, Long characterId, boolean readyBefore) { }

    public record SuspendResult(String message, Long scenePlanId, List<SuspensionUndo> undo) { }

    public record ResumeUndo(TrpgInvestigatorSuspension before, Long sceneItemId,
            boolean itemCreated, String participantStatusBefore, LocalDateTime itemUpdatedBefore) { }

    public record ResumeResult(String message, Long scenePlanId, Long tailPlanId,
            LocalDateTime tailUpdatedBefore, List<ResumeUndo> undo) { }
}
