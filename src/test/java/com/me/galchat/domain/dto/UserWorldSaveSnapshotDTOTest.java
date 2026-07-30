package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.GroupChatAgentDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserWorldSaveSnapshotDTOTest {

    @Test
    void groupTurnSnapshotCarriesAgentDecisions() {
        GroupChatAgentDecision decision =
                new GroupChatAgentDecision()
                        .setId(8L)
                        .setReplyStepId(41L)
                        .setContent("保留这段私有判断");

        var snapshot =
                new UserWorldSaveSnapshotDTO.GroupTurnSnapshot()
                        .setDecisions(List.of(decision));

        assertThat(snapshot.getDecisions())
                .containsExactly(decision);
    }
}
