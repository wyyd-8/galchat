package com.me.galchat.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorldArchiveImportResultDTO {

    private Long userWorldId;

    private Long worldId;

    private String name;

    private Integer detailCount;

    private Integer characterCount;
}
