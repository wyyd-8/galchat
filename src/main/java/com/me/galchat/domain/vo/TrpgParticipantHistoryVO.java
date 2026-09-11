package com.me.galchat.domain.vo;

import lombok.Data;

@Data
public class TrpgParticipantHistoryVO {
    private Long characterId;
    private long completedRunCount;
    private TrpgParticipantRunVO latestRun;
}
