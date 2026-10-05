package com.me.galchat.domain.dto;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public final class KpEquipmentDTOs {

    private KpEquipmentDTOs() {
    }

    public enum StashReason {
        DISCARDED,
        DISARMED,
        SEIZED
    }

    public enum PurchaseType {
        WEAPON,
        ITEM
    }

    public record PurchaseEntry(
            @ToolParam(description = "获得物品的人物卡准确名称")
            String characterName,
            @ToolParam(description = "WEAPON武器或ITEM普通物品")
            PurchaseType type,
            @ToolParam(description = "武器目录中的准确名称或直接写入物品栏的物品名称")
            String name) {
    }

    public record PurchaseRequest(
            @ToolParam(description = "本次同时获得的全部武器和物品")
            List<PurchaseEntry> entries) {
    }

    public record StashResult(
            Long weaponId,
            String weaponName,
            String sourceCharacterName,
            String locationName,
            StashReason reason, KpToolStateUndo undo) {
        public StashResult(Long weaponId, String weaponName, String sourceCharacterName, String locationName, StashReason reason) {
            this(weaponId, weaponName, sourceCharacterName, locationName, reason, null);
        }
    }

    public record EquipResult(
            Long weaponId,
            String weaponName,
            String targetCharacterName, KpToolStateUndo undo) {
        public EquipResult(Long weaponId, String weaponName, String targetCharacterName) {
            this(weaponId, weaponName, targetCharacterName, null);
        }
    }

    public record PurchaseLineResult(
            String characterName,
            PurchaseType type,
            String name) {
    }

    /** Stored with the tool result so retry can undo only this purchase. */
    public record PurchaseUndo(Long characterId, Long profileId,
                               String equipmentBefore, String equipmentAfter, Long weaponId) {
    }

    public record PurchaseResult(List<PurchaseLineResult> entries, List<PurchaseUndo> undo) {
    }
}
