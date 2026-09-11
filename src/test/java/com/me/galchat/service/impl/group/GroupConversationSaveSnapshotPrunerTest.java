package com.me.galchat.service.impl.group;

import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GroupConversationSaveSnapshotPrunerTest {

    @Test
    void removesDeletedConversationTurnsAndPlans() {
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
                                        .setConversationId(6L)));

        boolean changed = GroupConversationSaveSnapshotPruner.prune(
                snapshot, 7L);

        assertThat(changed).isTrue();
        assertThat(snapshot.getRecentGroupTurnsByConversation())
                .extracting(UserWorldSaveSnapshotDTO
                        .GroupConversationTurnsSnapshot::getConversationId)
                .containsExactly(6L);
        assertThat(snapshot.getConversationPlans())
                .extracting(UserWorldSaveSnapshotDTO
                        .GroupConversationPlanSnapshot::getConversationId)
                .containsExactly(6L);
    }

    @Test
    void preservesUnrelatedSnapshotData() {
        UserWorldSaveSnapshotDTO snapshot =
                new UserWorldSaveSnapshotDTO()
                        .setRecentGroupTurnsByConversation(List.of())
                        .setConversationPlans(List.of());

        boolean changed = GroupConversationSaveSnapshotPruner.prune(
                snapshot, 7L);

        assertThat(changed).isFalse();
    }
}
