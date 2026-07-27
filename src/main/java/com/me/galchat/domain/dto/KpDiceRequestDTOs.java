package com.me.galchat.domain.dto;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocPercentileModifier;

import java.util.List;

public final class KpDiceRequestDTOs {

    private KpDiceRequestDTOs() {
    }

    public record Check(
            String reason,
            CocCheckDifficulty difficulty,
            List<CheckTarget> targets) {
    }

    public record CheckTarget(
            String characterName,
            String checkName,
            CocPercentileModifier modifier) {
    }

    public record Opposed(
            String reason,
            List<CheckTarget> targets,
            String tieWinnerCharacterName) {
    }

    public record Pushed(
            String reason,
            List<String> characterNames) {
    }

    public record SanCheck(
            String reason,
            List<String> characterNames) {
    }
}
