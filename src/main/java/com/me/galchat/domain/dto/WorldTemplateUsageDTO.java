package com.me.galchat.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorldTemplateUsageDTO {

    private long associatedWorldCount;

    private boolean deletable;
}
