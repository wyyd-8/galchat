package com.me.galchat.constant;

import com.me.galchat.tool.KpDiceTools;
import com.me.galchat.tool.KpFirearmTools;
import com.me.galchat.tool.KpMeleeTools;
import com.me.galchat.tool.KpCombatTools;
import com.me.galchat.tool.KpModuleTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import static org.assertj.core.api.Assertions.assertThat;

class TrpgRulePromptConstantTest {

    @Test
    void publicAdjudicationNarratesObservableConsequencesWithoutMechanics() {
        assertThat(TrpgRulePromptConstant.KP_RESIDENT_RULES)
                .contains("骰点和工具结果只用于内部确定事实")
                .contains("具体伤害点数")
                .contains("HP 当前值、最大值或变化值")
                .contains("CON 检定失败")
                .contains("只描述角色能够观察到的动作过程、场面后果与状态变化");

        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("工具结果所对应的可观察后果")
                .contains("不得复述机械结算")
                .doesNotContain("工具确认的结果、目标是否生效");
    }

    @Test
    void startCombatToolDescriptionStopsBeforeTheCombatPlanBegins() {
        assertThat(description(
                new KpCombatTools(null, null), "startCombat"))
                .contains("当前步骤仍是战斗前的场景步骤")
                .contains("战斗尚未激活")
                .contains("不得描述先攻顺序、战斗轮或任何角色的新行动")
                .contains("确认参战者后立即结束回复");
    }

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
                .contains("`baseModifier` 只表示射程、瞄准、装填一发后立即射击")
                .contains("抵近射击范围为射手 DEX 的二十分之一米")
                .contains("基础射程内不因射程增加惩罚骰")
                .contains("超过基础射程且不超过两倍时计一颗射程惩罚骰")
                .contains("超过两倍且不超过四倍时计两颗射程惩罚骰")
                .contains("超过四倍时不可命中")
                .contains("净惩罚超过两颗时保留两颗惩罚骰")
                .contains("把超出部分逐级转为难度提升")
                .contains("`inCover=true` 自动增加一颗惩罚骰")
                .contains("`build<=-2` 自动视为小型目标")
                .contains("`shooterMovingFast=true`")
                .contains("`firingPostureRestricted=true`")
                .contains("`targetMovingFast=true`")
                .contains("不得把它们再次计入 `baseModifier`")
                .contains("速射、多次单发、短点射、全自动分组和转换目标")
                .contains("同一因素不能既提高难度又再给惩罚骰")
                .contains("没有明确显著因素时必须使用 `NORMAL`")
                .doesNotContain("射手 DEX 的五分之一");
    }

    @Test
    void sharedCombatReferenceDefinesDefenseRoutingWithoutToolProtocols() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_ACTION_REFERENCE)
                .contains("战斗专用近战胜负规则优先于常驻对抗规则")
                .contains("反击同级时攻击者胜")
                .contains("闪避同级时闪避者胜")
                .contains("NPC 默认优先反击")
                .contains("只有准备撤退时才优先闪避")
                .contains("枪械射击的目标不能用普通闪避或反击")
                .contains("察觉到枪械攻击且有躲避空间时才可选择寻找掩护")
                .contains("投掷武器可以闪避")
                .contains("抵近射击范围为射手 DEX 的二十分之一米")
                .doesNotContain("requestMeleeAttack", "requestFirearmAttack",
                        "tool_calls", "DSML");
    }

    @Test
    void combatRulesDescribeAimReloadShotFumbleAndArmorBoundaries() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_ACTION_REFERENCE)
                .contains("瞄准占用当前主动位")
                .contains("跳过本主动位的其余行动")
                .contains("后续回合")
                .contains("每个射击目标")
                .contains("一颗奖励骰")
                .contains("装填一发并立即射击")
                .contains("一颗惩罚骰");

        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("先调用 `updateWeaponState`")
                .contains("当前剩余弹药加1")
                .contains("再调用 `requestFirearmAttack`")
                .contains("每个目标的 `baseModifier` 手动加入一颗惩罚骰")
                .contains("自己、友方、友好或中立 NPC、路人")
                .contains("必须另行调用 `rollDamage`")
                .contains("近战和枪械专用工具自动读取目标人物卡护甲")
                .contains("枪械按每发命中分别减护甲")
                .contains("通用 `rollDamage` 不自动判断护甲是否生效")
                .contains("`max(0,(原 HP 伤害公式)-护甲值)`")
                .contains("`max(0,(1D3)-2)+眩晕`");
    }

    @Test
    void combatRulesProvideThreeEndToEndAttackExamples() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("#### 近战通用示例")
                .contains("#### 远程通用示例")
                .contains("#### 战技通用示例")
                .contains("调用前考虑", "调用顺序", "结果处理与解释")
                .containsSubsequence(
                        "目标选择寻找掩护",
                        "`requestCheck`",
                        "`updateCombatStates`",
                        "`requestFirearmAttack`");
    }

    @Test
    void combatManeuverExampleUsesBuildOppositionAndDeclaredFollowUps() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("体格比目标低1点时获得一颗惩罚骰")
                .contains("体格比目标低2点时获得两颗惩罚骰")
                .contains("体格比目标低3点或更多时战技不可行")
                .contains("`requestOpposedCheck`")
                .contains("同成功等级先比较人物卡检定值")
                .contains("只有人物卡检定值也相同才使用")
                .contains("声明击晕", "formula\":\"眩晕")
                .contains("声明控制", "`updateCombatStates`")
                .contains("restrainedByCharacterName\":\"林恩")
                .doesNotContain("体格差战技、持续控制")
                .doesNotContain("当前阶段仍不执行钳制规则");
    }

    @Test
    void restraintRulesExplainDisadvantageEscapeDefenseAndRelease() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_ACTION_REFERENCE)
                .contains("被钳制不会自动跳过主动位")
                .contains("自己的主动位施展挣脱战技")
                .contains("一颗惩罚骰")
                .contains("一颗奖励骰")
                .contains("同一项持续劣势不能同时计算两次")
                .contains("被钳制状态本身不等于完全无法防守");

        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("卸除武器或夺取物品")
                .contains("撞下悬崖、推出窗外或推倒在地")
                .contains("钳制者主动松开、无法继续压制或受到重伤")
                .contains("具体钳制方式确实使目标没有闪避或反击能力")
                .containsSubsequence(
                        "被钳制者声明挣脱",
                        "`requestOpposedCheck`",
                        "restrainedByCharacterName\":\"\"")
                .contains("按情境调用 `rollDamage`");

        assertThat(description(
                new KpCombatTools(null, null), "updateCombatStates"))
                .contains("空字符串解除钳制")
                .contains("重伤");
    }

    @Test
    void reloadGuidanceLimitsLooseRoundLoadingToTwoAndExplainsLoadAndFire() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_ACTION_REFERENCE)
                .contains("逐发装填")
                .contains("一个主动位最多增加两发弹药")
                .contains("只装填一发并立即射击")
                .contains("一颗惩罚骰");

        assertThat(description(
                new KpModuleTools(null, null, null), "updateWeaponState"))
                .contains("逐发装填")
                .contains("最多增加两发");
    }

    @Test
    void combatDtoToolExamplesUseTheirRequestParameterWrapper() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains(
                        "`{\"request\":{\"reason\":\"用折刀刺击邪教徒\"",
                        "`{\"request\":{\"reason\":\"用手枪射击林恩\"",
                        "`{\"request\":{\"reason\":\"抓住并控制邪教徒\"",
                        "`{\"request\":{\"reason\":\"坠落伤害\"")
                .doesNotContain(
                        "`{\"reason\":\"用折刀刺击邪教徒\"",
                        "`{\"reason\":\"用手枪射击林恩\"",
                        "`{\"reason\":\"抓住并控制邪教徒\"",
                        "`{\"reason\":\"坠落伤害\"");
    }

    @Test
    void combatMultiParameterToolExampleStaysFlat() {
        assertThat(TrpgRulePromptConstant.KP_COMBAT_RULES)
                .contains("`{\"characterName\":\"林恩\",\"weaponName\":"
                        + "\"左轮手枪\",\"update\":{\"remainingAmmo\":6}}`")
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
        assertThat(schema(new KpDiceTools(null), "requestOpposedCheck"))
                .contains("\"request\" : {", "\"required\" : [ \"request\" ]");
        assertThat(schema(new KpCombatTools(null, null),
                "updateCombatStates"))
                .contains("\"update\" : {", "\"required\" : [ \"update\" ]");
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

    private String description(Object tool, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tool))
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(name))
                .findFirst()
                .orElseThrow()
                .getToolDefinition()
                .description();
    }
}
