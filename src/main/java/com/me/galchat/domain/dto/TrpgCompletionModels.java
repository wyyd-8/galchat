package com.me.galchat.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class TrpgCompletionModels {
    private TrpgCompletionModels() {}

    // Written once, only when the entire summary turn succeeds.
    public record Data(Materials materials, List<TrpgEpilogueModels.Entry> epilogues, Overview overview) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Materials(String title, String coverUrl, long endSequence,
                            int turnCount, List<Source> sources, List<Investigator> investigators,
                            List<Roll> rolls) {}
    public record Source(long startSequence, long endSequence, String text) {}
    public record Investigator(TrpgEpilogueModels.Subject subject, String image, boolean player,
                               Integer initialHp, Integer initialSan) {}
    public record Roll(Long characterId, int turnNo, String checkName, Integer value,
                       Integer target, String outcome) {}
    public record Overview(String summary, String ending, List<Chapter> journey) {}
    public record Chapter(int sourceIndex, String title, String excerpt) {}

    // Public response deliberately omits character background and unused generation materials.
    public record Report(String status, LocalDateTime completedAt, LocalDateTime archivedAt,
                         String title, String coverUrl, String ending, int turnCount,
                         List<Journey> journey, List<Person> investigators, List<Roll> rolls) {}
    public record Journey(String title, String excerpt, String summary) {}
    public record Person(Long characterId, String name, String occupation, String image, boolean player,
                         boolean dead, boolean dying, boolean unconscious, boolean majorWound,
                         boolean temporaryInsanity, Integer initialHp, Integer hp,
                         Integer initialSan, Integer san, String lead, String epilogue) {}
}
