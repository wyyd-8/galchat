package com.me.galchat.domain.dto;

import org.springframework.ai.tool.annotation.ToolParam;

public final class KpWeaponStateDTOs {

    private KpWeaponStateDTOs() {
    }

    public record Update(
            @ToolParam(
                    description = "更新后的剩余弹药；射击可一次减少多发，装填可增加但不能超过容量；不修改时省略",
                    required = false)
            Integer remainingAmmo,
            @ToolParam(
                    description = "更新后的损坏状态；可用于标记故障或在修复行动成功后解除损坏",
                    required = false)
            Boolean broken) {
    }

    public record Result(
            String characterName,
            String weaponName,
            Integer remainingAmmo,
            Integer ammoCapacity,
            boolean broken,
            boolean changed) {
    }
}
