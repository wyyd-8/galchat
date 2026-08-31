package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.po.WorldEventLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupConversationSaveSnapshotPrunerTest {

    @Test
    void removesDeletedConversationTurnsPlansAndLastWorldEvent() {
        WorldEventLog deletedEvent = new WorldEventLog()
                .setId(31L)
                .setConversationId(7L);
        WorldEventLog replacement = new WorldEventLog()
                .setId(30L)
                .setConversationId(6L);
        UserWorldSaveSnapshotDTO snapshot =
                new UserWorldSaveSnapshotDTO()
                        .setRecentGroupTurnsByConversation(List.of(
                                new UserWorldSaveSnapshotDTO
                                        .GroupConversationTurnsSnapshot()
                                        .setConversationId(6L),
                                new UserWorldSaveSnapshotDTO
                                        .GroupConversationTurnsSnapshot()
                                        .setConversationId(7L)))
                        .setConversationPlans(List.of(
                                new UserWorldSaveSnapshotDTO
                                        .GroupConversationPlanSnapshot()
                                        .setConversationId(7L),
                                new UserWorldSaveSnapshotDTO
                                        .GroupConversationPlanSnapshot()
                                        .setConversationId(6L)))
                        .setLastWorldEventLog(deletedEvent);

        boolean changed = GroupConversationSaveSnapshotPruner.prune(
                snapshot, 7L, replacement);

        assertThat(changed).isTrue();
        assertThat(snapshot.getRecentGroupTurnsByConversation())
                .extracting(UserWorldSaveSnapshotDTO
                        .GroupConversationTurnsSnapshot::getConversationId)
                .containsExactly(6L);
        assertThat(snapshot.getConversationPlans())
                .extracting(UserWorldSaveSnapshotDTO
                        .GroupConversationPlanSnapshot::getConversationId)
                .containsExactly(6L);
        assertThat(snapshot.getLastWorldEventLog()).isSameAs(replacement);
    }

    @Test
    void preservesUnrelatedSnapshotData() {
        WorldEventLog event = new WorldEventLog()
                .setId(31L)
                .setConversationId(6L);
        UserWorldSaveSnapshotDTO snapshot =
                new UserWorldSaveSnapshotDTO()
                        .setRecentGroupTurnsByConversation(List.of())
                        .setConversationPlans(List.of())
                        .setLastWorldEventLog(event);

        boolean changed = GroupConversationSaveSnapshotPruner.prune(
                snapshot, 7L, null);

        assertThat(changed).isFalse();
        assertThat(snapshot.getLastWorldEventLog()).isSameAs(event);
    }
}
