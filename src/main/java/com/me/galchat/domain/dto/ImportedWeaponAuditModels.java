package com.me.galchat.domain.dto;

import java.util.List;

public final class ImportedWeaponAuditModels {

    private ImportedWeaponAuditModels() {
    }

    public record Review(
            Long weaponId,
            Boolean abnormal,
            List<String> riskTags) {
    }

    public record Response(List<Review> weapons) {
    }
}
