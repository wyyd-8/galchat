package com.me.galchat.domain.dto;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public final class KpCombatStateDTOs {

    private KpCombatStateDTOs() {
    }

    public record Update(
            @ToolParam(description = "需要修改的参战人物卡状态；同一人物卡只能出现一次")
            List<Change> changes) {
    }

    public record Change(
            @ToolParam(description = "目标的准确人物卡名称")
            String characterName,
            @ToolParam(description = "是否处于掩护；省略表示不修改", required = false)
            Boolean inCover,
            @ToolParam(description = "是否仍需因寻找掩护失去下一主动位；省略表示不修改", required = false)
            Boolean coverActionForfeitPending,
            @ToolParam(description = "钳制者的准确人物卡名称；空字符串表示解除钳制，省略表示不修改", required = false)
            String restrainedByCharacterName) {
    }

    public record State(
            String characterName,
            boolean inCover,
            boolean coverActionForfeitPending,
            String restrainedByCharacterName) {
    }

    public record Result(List<State> states) {
    }
}
