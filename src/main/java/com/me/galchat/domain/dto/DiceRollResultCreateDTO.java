package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class DiceRollResultCreateDTO {
    private Long characterId;
    private Integer displayOrder;
    private String displayType;
    private String reason;
    private String formula;
}
