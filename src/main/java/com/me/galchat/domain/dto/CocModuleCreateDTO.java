package com.me.galchat.domain.dto;

import lombok.Data;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Data
public class CocModuleCreateDTO {

    private String name;
    private String author;
    private String era;
    private String introduction;
    private String investigatorCreation;
    private String coverUrl;
    private String playerCount;
    private String estimatedDuration;
    private Boolean visible;
    private GlobalContext context;
    private List<Location> locations;
    private List<Clue> clues;
    private List<Material> materials;
    private List<JsonNode> characters;

    @Data
    public static class GlobalContext {
        private String truthBackground;
        private String investigatorIntro;
        private String timeline;
        private String specialRules;
        private String keeperGuidance;
        private String endingContent;
        private String extraContent;
    }

    @Data
    public static class Location {
        private String parentName;
        private String name;
        private String summary;
        private String content;
    }

    @Data
    public static class Clue {
        private String title;
        private String content;
        private Boolean important;
    }

    @Data
    public static class Material {
        private String title;
        private String description;
        private String imageUrl;
    }
}
