package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class KpSkillRuleToolsTest {

    @Test
    void modelSchemaExposesOnlySkillNames() {
        String schema = ToolCallbacks.from(
                        new KpSkillRuleTools())[0]
                .getToolDefinition().inputSchema();

        assertThat(schema)
                .contains("\"skillNames\"")
                .doesNotContain("reason", "scene", "characterName");
    }

    @Test
    void kpReceivesDescriptionsFromTheInMemoryMapInRequestOrder() {
        KpSkillRuleTools tools = new KpSkillRuleTools();

        Map<String, String> result = tools.readSkillRules(
                List.of(" 侦查 ", "格斗:刀剑", "侦查"),
                context(GroupChatConstant.ACTOR_KP));

        assertThat(result).containsOnlyKeys("侦查", "格斗:刀剑");
        assertThat(result.get("侦查")).contains("技能：侦查");
        assertThat(result.get("格斗:刀剑"))
                .contains("技能：格斗:刀剑");
    }

    @Test
    void combatSkillRulesExposeEraGroupedAcquisitionCatalogs() {
        KpSkillRuleTools tools = new KpSkillRuleTools();

        Map<String, String> result = tools.readSkillRules(
                List.of("格斗:斧", "射击:手枪"),
                context(GroupChatConstant.ACTOR_KP));

        assertThat(result.get("格斗:斧"))
                .contains("### 可用冷兵器列表")
                .contains("#### 通用", "黄铜指虎")
                .contains("#### 1920s", "长鞭")
                .contains("#### 现代", "链锯");
        assertThat(result.get("射击:手枪"))
                .contains("### 可用远程武器列表")
                .contains("| 名称 | 伤害 | 射程 | 弹容量 | 获取级别 |")
                .contains("| 弓箭 |", "| 弩 |", "| 泰瑟枪 |")
                .contains("| .38/9mm左轮手枪 | 1D10 | 15m | 6 | 普通 |")
                .contains("| 12号双管霰弹枪 | 4D6/2D6/1D6 | 近≤10m；中≤20m；远≤50m | 2 | 普通 |")
                .contains("#### 1920s", "汤普森冲锋枪", "受管制")
                .contains("#### 现代", "AK-47/AKM", "H&K MP5")
                .contains("使用requestFirearmAttack")
                .contains("NEAR", "MEDIUM", "FAR")
                .contains("短点射和全自动")
                .doesNotContain("| 所需技能 |", "| 价格 |", "| 故障值 |");
    }

    @Test
    void dodgeRuleUsesCombatSpecificTieResolutionInsteadOfSkillValues() {
        KpSkillRuleTools tools = new KpSkillRuleTools();

        String dodgeRule = tools.readSkillRules(
                List.of("闪避"), context(GroupChatConstant.ACTOR_KP))
                .get("闪避");

        assertThat(dodgeRule)
                .contains("只比较成功等级")
                .contains("同级时闪避者胜")
                .contains("同级时攻击者胜")
                .doesNotContain("先比较成功等级，再比较人物卡检定值");
    }

    @Test
    void unknownAndBlankSkillNamesAreRejected() {
        KpSkillRuleTools tools = new KpSkillRuleTools();

        assertThatThrownBy(() -> tools.readSkillRules(
                List.of("观察"), context(GroupChatConstant.ACTOR_KP)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("观察")
                .hasMessageContaining("技能清单");
        assertThatThrownBy(() -> tools.readSkillRules(
                List.of("  "), context(GroupChatConstant.ACTOR_KP)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("技能名称");
    }

    @Test
    void nonKpIsRejected() {
        KpSkillRuleTools tools = new KpSkillRuleTools();

        assertThatThrownBy(() -> tools.readSkillRules(
                List.of("侦查"),
                context(GroupChatConstant.ACTOR_CHARACTER)))
                .isInstanceOf(UserAuthException.class);
    }

    private ToolContext context(String actorType) {
        return new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY, actorType));
    }
}
