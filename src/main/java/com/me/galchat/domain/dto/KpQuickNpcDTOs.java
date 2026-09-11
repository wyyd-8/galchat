package com.me.galchat.domain.dto;

import org.springframework.ai.tool.annotation.ToolParam;

public final class KpQuickNpcDTOs {

    private KpQuickNpcDTOs() {
    }

    public record Spec(
            @ToolParam(description = "临时 NPC 的人物卡名称")
            String name,
            @ToolParam(description = "强度档位：WEAK、MEDIUM 或 STRONG")
            String strength,
            @ToolParam(description = "典型武器：UNARMED、LARGE_CLUB、MEDIUM_KNIFE、PISTOL、SMALL_RIFLE 或 HUNTING_RIFLE")
            String weapon) {
    }
}
