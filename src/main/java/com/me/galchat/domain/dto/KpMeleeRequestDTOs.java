package com.me.galchat.domain.dto;

import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.MeleeDefenseMode;
import org.springframework.ai.tool.annotation.ToolParam;

import static com.me.galchat.domain.dto.KpDiceRequestDTOs.SELF_CONTAINED_REASON;

public final class KpMeleeRequestDTOs {

    private KpMeleeRequestDTOs() {
    }

    public record Attack(
            @ToolParam(description = SELF_CONTAINED_REASON
                    + "近战攻击写明谁用什么方式攻击谁，以及对方如何防守或反击；不要写规则或预期结果")
            String reason,
            @ToolParam(description = "攻击者、攻击武器和攻击检定奖惩骰")
            Attacker attacker,
            @ToolParam(description = "防守者、防守方式、可选反击武器和防守检定奖惩骰")
            Defender defender) {
    }

    public record Attacker(
            @ToolParam(description = "攻击者的准确人物卡名称")
            String characterName,
            @ToolParam(description = "人物卡中的准确武器名称；省略表示徒手", required = false)
            String weaponName,
            @ToolParam(description = "攻击检定的基础奖惩骰；省略时为NORMAL", required = false)
            CocPercentileModifier modifier) {
    }

    public record Defender(
            @ToolParam(description = "防守者的准确人物卡名称")
            String characterName,
            @ToolParam(description = "DODGE闪避、COUNTERATTACK反击、NONE无防守能力")
            MeleeDefenseMode defenseMode,
            @ToolParam(description = "反击使用的人物卡武器；仅COUNTERATTACK可填，省略表示徒手", required = false)
            String counterWeaponName,
            @ToolParam(description = "闪避或反击检定的基础奖惩骰；省略时为NORMAL", required = false)
            CocPercentileModifier modifier) {
    }
}
