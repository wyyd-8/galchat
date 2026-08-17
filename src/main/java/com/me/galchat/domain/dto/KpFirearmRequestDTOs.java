package com.me.galchat.domain.dto;

import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.FirearmFiringMode;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public final class KpFirearmRequestDTOs {

    private KpFirearmRequestDTOs() {
    }

    public record Attack(
            @ToolParam(description = "用简短短语概括本轮枪械攻击")
            String reason,
            @ToolParam(description = "开火角色的准确人物卡名称")
            String characterName,
            @ToolParam(description = "人物卡中的准确武器名称")
            String weaponName,
            @ToolParam(description = "射击方式：SINGLE单发、HANDGUN_MULTIPLE手枪连射、SEMI_AUTO半自动多次单发、SHORT_BURST短点射、FULL_AUTO全自动")
            FirearmFiringMode firingMode,
            @ToolParam(description = "若故障阈值上的结果同时为大失败，是否选择武器故障；false表示由KP在工具完成后给出误伤等其他后果")
            boolean fumbleBreaksWeapon,
            @ToolParam(description = "按声明顺序列出本轮全部射击目标、为该目标分配的子弹和基础奖惩骰")
            List<Target> targets) {
    }

    public record Target(
            @ToolParam(description = "射击目标的准确人物卡名称")
            String targetCharacterName,
            @ToolParam(description = "声明向该目标发射的子弹数；弹药不足时后端只分配剩余弹药，之后的目标不再检定")
            int bulletCount,
            @ToolParam(description = "仅包含距离、掩护等场景因素的基础修正，不要包含连射、多次检定或转换目标自动产生的惩罚骰；省略时为NORMAL", required = false)
            CocPercentileModifier baseModifier) {
    }
}
