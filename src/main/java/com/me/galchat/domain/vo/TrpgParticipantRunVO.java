package com.me.galchat.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class TrpgParticipantRunVO {
    private Long conversationId;
    private String title;
    private Long moduleId;
    private String moduleName;
    private String status;
    private LocalDateTime lastPlayedAt;
    private LocalDateTime completedAt;
}
