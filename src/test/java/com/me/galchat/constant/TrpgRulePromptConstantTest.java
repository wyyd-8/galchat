package com.me.galchat.constant;

import com.me.galchat.tool.KpDiceTools;
import com.me.galchat.tool.KpFirearmTools;
import com.me.galchat.tool.KpMeleeTools;
import com.me.galchat.tool.KpModuleTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import static org.assertj.core.api.Assertions.assertThat;

class TrpgRulePromptConstantTest {

    @Test
    void combatAdjudicationRulesMapFiringModesAndSceneModifiers() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("近战攻击方与防守方分别判断自己的场景修正")
                .contains("明确成立的突袭、居高或稳定优势、对手动作受限")
                .contains("严重视线受阻、立足不稳、姿势受限或武器难以施展")
                .contains("技能值高低、剩余 HP、武器伤害、角色重要性")
                .contains("不能仅因攻击者使用枪械近战就追加奖惩骰")
                .contains("`SINGLE`：只发射1发")
                .contains("`HANDGUN_MULTIPLE`：手枪在 `1（n）` 允许范围内连续射击2至n发")
                .contains("`SEMI_AUTO`：非手枪半自动武器在 `1（n）` 允许范围内连续射击2至n发")
                .contains("`SHORT_BURST`：只用于人物卡明确写出的固定发数点射")
                .contains("`FULL_AUTO`：只用于人物卡明确写有“全自动”的武器")
                .contains("`bulletCount` 是实际分配给目标的子弹数")
                .contains("`baseModifier` 只表示当前场景条件")
                .contains("抵近射击（距离不超过射手 DEX 的五分之一）")
                .contains("部分掩护、昏暗或视线受阻、射手或目标快速移动、小目标")
                .contains("速射、多次单发、短点射、全自动分组和转换目标")
                .contains("同一因素不能既提高难度又再给惩罚骰")
                .contains("没有明确显著因素时必须使用 `NORMAL`");
    }

    @Test
    void combatDtoToolExamplesUseTheirRequestParameterWrapper() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains(
                        "`{\"request\":{\"reason\":\"用折刀刺击邪教徒\"",
                        "`{\"request\":{\"reason\":\"向两名邪教徒扫射\"",
                        "`{\"request\":{\"reason\":\"坠落伤害\"")
                .doesNotContain(
                        "`{\"reason\":\"用折刀刺击邪教徒\"",
                        "`{\"reason\":\"向两名邪教徒扫射\"",
                        "`{\"reason\":\"坠落伤害\"");
    }

    @Test
    void combatMultiParameterToolExampleStaysFlat() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("`{\"characterName\":\"林恩\",\"weaponName\":"
                        + "\"左轮手枪\",\"update\":{\"remainingAmmo\":6,"
                        + "\"broken\":false}}`")
                .doesNotContain("`{\"request\":{\"characterName\":\"林恩\","
                        + "\"weaponName\":\"左轮手枪\"");
    }

    @Test
    void combatExampleShapesMatchGeneratedToolSchemas() {
        assertThat(schema(new KpMeleeTools(null), "requestMeleeAttack"))
                .contains("\"request\" : {", "\"required\" : [ \"request\" ]");
        assertThat(schema(new KpFirearmTools(null), "requestFirearmAttack"))
                .contains("\"request\" : {", "\"required\" : [ \"request\" ]");
        assertThat(schema(new KpDiceTools(null), "rollDamage"))
                .contains("\"request\" : {", "\"required\" : [ \"request\" ]");
        assertThat(schema(
                new KpModuleTools(null, null, null), "updateWeaponState"))
                .contains("\"characterName\" : {", "\"weaponName\" : {",
                        "\"update\" : {")
                .doesNotContain("\"required\" : [ \"request\" ]");
    }

    private String schema(Object tool, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tool))
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(name))
                .findFirst()
                .orElseThrow()
                .getToolDefinition()
                .inputSchema();
    }
}
