package com.me.galchat.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class TrpgRollbackResultVO {

    private String checkpointType;
    private LocalDateTime savedAt;
    private Boolean manualSaveDeleted;
}
