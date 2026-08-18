package com.me.galchat.domain.dto;

import java.util.List;

public final class ImportedWeaponAuditModels {

    private ImportedWeaponAuditModels() {
    }

    public record Review(
            Long weaponId,
            String catalogCode) {
    }

    public record Response(List<Review> weapons) {
    }
}
