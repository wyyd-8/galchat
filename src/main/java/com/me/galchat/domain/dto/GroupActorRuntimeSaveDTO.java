package com.me.galchat.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class GroupActorRuntimeSaveDTO {
    private String actorType;
    private Long actorId;
    private String controlMode;
    private Long modelApiId;
}
