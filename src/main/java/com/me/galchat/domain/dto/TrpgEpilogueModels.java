package com.me.galchat.domain.dto;

import java.util.List;

public final class TrpgEpilogueModels {

    private TrpgEpilogueModels() {
    }

    public record Subject(
            Long characterId,
            String investigatorName,
            String occupation,
            Integer hpCurrent,
            Integer hpMax,
            Integer sanCurrent,
            Integer sanMax,
            boolean dead,
            boolean dying,
            boolean unconscious,
            boolean majorWound,
            boolean temporaryInsanity,
            String injuriesAndScars,
            String phobiasAndManias,
            String ideology,
            String significantPeople,
            String meaningfulLocations,
            String treasuredPossessions,
            String traits,
            String keyConnectionText) {
    }

    public record Entry(
            Long characterId,
            String investigatorName,
            String lead,
            String content) {
    }

    public record Response(List<Entry> entries) {
    }

    public record Content(int schemaVersion, List<Entry> entries) {
    }
}
