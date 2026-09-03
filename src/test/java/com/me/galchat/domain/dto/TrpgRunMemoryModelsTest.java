package com.me.galchat.domain.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrpgRunMemoryModelsTest {

    @Test
    void activeDetailsOmitFieldsThatOnlyApplyToClosedRuns() {
        var details = new TrpgRunMemoryModels.RunDetails(
                71L, "模组", "active", null,
                new TrpgRunMemoryModels.OwnDiceStatistics(0, 0, 0, 0, 0),
                null, null, List.of(), null,
                null, null, null, null, null,
                TrpgRunMemoryModels.SNAPSHOT_NOTICE);

        String json = JsonMapper.builder().build()
                .writeValueAsString(details);

        assertThat(json)
                .doesNotContain("closedAt", "summary", "latestPublicMessage")
                .contains("\"status\":\"active\"");
    }
}
