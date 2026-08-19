package com.me.galchat.domain.dto;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.HealingSourceMode;
import com.me.galchat.constant.HealingMode;
import com.me.galchat.constant.GroupCheckRule;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public final class KpDiceRequestDTOs {

    private KpDiceRequestDTOs() {
    }

    public record Check(
            @ToolParam(description = "用简短短语概括本次检定原因；不要复述行动过程、规则或预期结果。会作为掷骰概要和前端展示文本")
            String reason,
            @ToolParam(
                    description = "检定难度：REGULAR普通、HARD困难、EXTREME极难；省略时为REGULAR",
                    required = false)
            CocCheckDifficulty difficulty,
            @ToolParam(description = "参与单人检定的角色及候选检定项；多个候选项只掷一次并使用其中最高值")
            CheckTarget target) {
    }

    public record GroupCheck(
            @ToolParam(description = "用简短短语概括本次群体检定原因；不要复述行动过程、规则或预期结果。会作为掷骰概要和前端展示文本")
            String reason,
            @ToolParam(
                    description = "检定难度：REGULAR普通、HARD困难、EXTREME极难；省略时为REGULAR",
                    required = false)
            CocCheckDifficulty difficulty,
            @ToolParam(description = "群体通过规则，仅供前端展示，不改变各角色的实际检定结果："
                    + "ANY_SUCCESS任一成功即通过，例如群体聆听；"
                    + "ALL_SUCCESS全部成功才通过，例如群体潜行；"
                    + "SEPARATE分别展示、不计算群体结论；不确定时使用SEPARATE（默认）")
            GroupCheckRule groupRule,
            @ToolParam(description = "参与群体检定的角色及候选检定项；每个角色只能出现一次，"
                    + "同一角色的多个候选项必须放入同一个CheckTarget，后端只掷一次并使用其中最高值")
            List<CheckTarget> targets) {
    }

    public record CheckTarget(
            @ToolParam(description = "参与检定的角色名，必须与当前跑团中的角色卡名称一致")
            String characterName,
            @ToolParam(description = "角色卡上的候选属性或技能名称，例如力量、侦查、手枪；后端使用其中检定值最高的一项")
            List<String> checkNames,
            @ToolParam(
                    description = "百分骰修正：NORMAL无修正，BONUS_1/BONUS_2奖励骰，PENALTY_1/PENALTY_2惩罚骰；省略时为NORMAL",
                    required = false)
            CocPercentileModifier modifier) {

        public CheckTarget(
                String characterName,
                String checkName,
                CocPercentileModifier modifier) {
            this(characterName,
                    checkName == null ? null : List.of(checkName),
                    modifier);
        }
    }

    public record Opposed(
            @ToolParam(description = "用简短短语概括本次对抗原因；不要复述行动过程、规则或预期结果。会作为掷骰概要和前端展示文本")
            String reason,
            @ToolParam(description = "参与对抗的角色及其检定项，至少包含两个不同角色")
            List<CheckTarget> targets,
            @ToolParam(
                    description = "规则明确平局时由谁获胜则填写该角色名；没有明确胜者时省略，结果将保持平局",
                    required = false)
            String tieWinnerCharacterName) {
    }

    public record Pushed(
            @ToolParam(description = "用简短短语概括孤注一掷原因或玩家的新行动；不要复述完整行动过程、规则或预期结果")
            String reason,
            @ToolParam(description = "需要对最近一次失败检定进行孤注一掷的角色名列表")
            List<String> characterNames) {
    }

    public record SanCheck(
            @ToolParam(description = "用简短短语概括触发理智检定的原因；不要复述完整场景、规则或预期结果。会作为掷骰概要和前端展示文本")
            String reason,
            @ToolParam(description = "需要按当前SAN值进行理智检定的角色名列表")
            List<String> characterNames) {
    }

    public record SanLoss(
            @ToolParam(description = "用简短短语概括理智损失原因；不要复述完整场景、规则或结果。会作为新增掷骰轮的展示文本")
            String reason,
            @ToolParam(description = "上一轮理智检定成功时使用的SAN损失表达式，例如0或1")
            String successFormula,
            @ToolParam(description = "上一轮理智检定失败时使用的SAN损失表达式，例如1D6")
            String failureFormula) {
    }

    public record Damage(
            @ToolParam(description = "用简短短语概括伤害原因；不要复述行动过程、规则或结果。会作为掷骰概要和前端展示文本")
            String reason,
            @ToolParam(description = "本轮各受伤角色及对应伤害表达式")
            List<DamageTarget> targets) {
    }

    public record DamageTarget(
            @ToolParam(description = "承受伤害的角色名，必须与当前跑团中的角色卡名称一致")
            String targetCharacterName,
            @ToolParam(description = "对该目标执行的伤害表达式，例如1D6、1D8+2、1D3+眩晕或纯眩晕；眩晕必须是独立加数")
            String formula) {
    }

    public record Healing(
            @ToolParam(description = "用简短短语概括恢复生命的原因；不要复述行动过程、规则或结果。会作为掷骰概要或新增掷骰轮的展示文本")
            String reason,
            @ToolParam(description = "回血来源模式：STANDALONE无来源回血，FOLLOW_UP单次检定成功后的回血")
            HealingSourceMode sourceMode,
            @ToolParam(description = "恢复方式：FIRST_AID急救、MEDICINE医学、OTHER其他来源")
            HealingMode mode,
            @ToolParam(description = "本轮各回血角色、可选前置来源角色及对应回血表达式")
            List<HealingTarget> targets) {
    }

    public record HealingTarget(
            @ToolParam(description = "恢复生命的角色名，必须与当前跑团中的角色卡名称一致")
            String targetCharacterName,
            @ToolParam(
                    description = "FOLLOW_UP时必填，填写完成前置单次检定的角色名；STANDALONE时必须省略",
                    required = false)
            String sourceCharacterName,
            @ToolParam(description = "对该目标执行的回血表达式，例如1或1D3")
            String formula) {
    }
}
