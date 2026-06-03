package com.me.galchat.domain.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorldArchiveDTO {

    private Integer formatVersion;

    private WorldArchive world;

    private List<WorldDetailArchive> details;

    private List<CharacterArchive> characters;

    @Data
    @Accessors(chain = true)
    public static class WorldArchive {
        private String name;
        private String image;
        private String author;
        private String background;
        private Boolean visible;
    }

    @Data
    @Accessors(chain = true)
    public static class WorldDetailArchive {
        private String about;
        private String details;
    }

    @Data
    @Accessors(chain = true)
    public static class CharacterArchive {
        private String name;
        private String image;
        private String background;
        private String personality;
        private Map<String, String> favorability;
        private Integer initFavor;
    }
}
