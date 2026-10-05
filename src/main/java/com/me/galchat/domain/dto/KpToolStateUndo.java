package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.*;
import java.time.LocalDateTime;
import java.util.List;

/** Only fields owned by the tool are restored; unrelated character state is left intact. */
public record KpToolStateUndo(Long runId, List<CardChange> cards, WeaponChange weapon) {
    public record CardState(Long id, String quickNotes, Boolean inCover,
                            Boolean coverActionForfeitPending, Long restrainedByCharacterId,
                            LocalDateTime updatedAt) {
        public static CardState of(CocCharacter card) {
            return new CardState(card.getId(), card.getQuickNotes(), card.getInCover(),
                    card.getCoverActionForfeitPending(), card.getRestrainedByCharacterId(), card.getUpdatedAt());
        }
    }
    public record CardChange(CardState before, CardState after) {}
    public record WeaponChange(CocCharacterWeapon before, CocCharacterWeapon after,
                               TrpgWeaponStash stashBefore, TrpgWeaponStash stashAfter) {}
    public record NotesResult(String message, KpToolStateUndo undo) {}

    public static CocCharacterWeapon copy(CocCharacterWeapon source) {
        if (source == null) return null;
        var target = new CocCharacterWeapon();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
