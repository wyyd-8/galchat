package com.me.galchat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class WorldStoryEventStartDTO {
    private Long userWorldId;
    private String title;
    private String theme;
    private String currentScene;
    private String opening;
    private List<Long> characterIds;
}
