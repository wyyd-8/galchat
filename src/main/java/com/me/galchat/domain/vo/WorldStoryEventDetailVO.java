package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class WorldStoryEventDetailVO {
    private Long id;
    private Long userWorldId;
    private Long conversationId;
    private String title;
    private String theme;
    private String currentScene;
    private String opening;
    private String summary;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private List<String> characterNames;
}
