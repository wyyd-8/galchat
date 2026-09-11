package com.me.galchat.domain.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorldArchiveReplaceResultDTO {

    private Long worldId;

    private String name;

    private int detailCount;

    private int characterCount;

    private int matchedCharacterCount;

    private int addedCharacterCount;

    private int unchangedCharacterCount;

    private double matchRate;

    private boolean confirmationRequired;

    private boolean replaced;

    private List<String> matchedCharacterNames;

    private List<String> addedCharacterNames;

    private List<String> unchangedCharacterNames;
}
