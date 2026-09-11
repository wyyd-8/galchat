package com.me.galchat.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

public final class KpCharacterAttributeDTOs {

    private KpCharacterAttributeDTOs() {
    }

    public record Adjustments(
            @ToolParam(description = "STR力量修正值", required = false)
            Integer str,
            @ToolParam(description = "CON体质修正值", required = false)
            Integer con,
            @ToolParam(description = "SIZ体型修正值", required = false)
            Integer siz,
            @ToolParam(description = "DEX敏捷修正值", required = false)
            Integer dex,
            @ToolParam(description = "APP外貌修正值", required = false)
            Integer app,
            @JsonProperty("int")
            @ToolParam(description = "INT智力修正值", required = false)
            Integer intValue,
            @ToolParam(description = "POW意志修正值", required = false)
            Integer pow,
            @ToolParam(description = "EDU教育修正值", required = false)
            Integer edu) {
    }

    public record ValueChange(int before, int after) {
    }

    public record Result(
            String characterName,
            Map<String, ValueChange> changes,
            String damageBonus,
            int build) {
    }
}
