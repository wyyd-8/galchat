package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class CharacterCardCreateDTO {
    private Long runId;
    private Long participantId;
    private String characterText;
}
