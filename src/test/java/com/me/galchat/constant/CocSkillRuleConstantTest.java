package com.me.galchat.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CocSkillRuleConstantTest {

    @Test
    void codeCatalogCoversEverySystemSkillWithUniqueExactNames() {
        var rules = CocSkillRuleConstant.RULES;

        assertThat(rules).hasSize(86);
        assertThat(rules)
                .extracting(CocSkillRuleConstant.SkillRule::skillName)
                .doesNotHaveDuplicates()
                .contains("侦查", "格斗", "格斗:斧", "斗殴",
                        "科学:工程学", "学识");
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.briefDescription()).isNotBlank();
            assertThat(rule.description()).isNotBlank()
                    .contains(rule.skillName());
        });
        assertThat(CocSkillRuleConstant.SKILL_RULES_BY_NAME)
                .hasSize(86)
                .containsKeys("侦查", "格斗:刀剑")
                .allSatisfy((name, description) ->
                        assertThat(description).contains(name));
    }

    @Test
    void kpIndexExplainsOmittedChecksAndMarksOnlySpecializedCategories() {
        assertThat(CocSkillRuleConstant.KP_SKILL_INDEX)
                .contains("基础值且基础值≤5", "仍可发起检定", "以kp-skill-index为准")
                .contains("- 人类学：理解社会结构")
                .contains("- 格斗：大类：", "- 科学：大类：", "- 艺术和手艺：大类：",
                        "- 射击：大类：", "- 语言：大类：", "- 生存：大类：",
                        "- 操纵：大类：", "- 学识：大类：")
                .doesNotContain("范围：", "- 斗殴：大类：", "- 母语：大类：", "- 科学:化学：大类：");
    }

    @Test
    void shootingRuleListsEverySupportedRangedWeaponForAcquisition() {
        String shooting = CocSkillRuleConstant.SKILL_RULES_BY_NAME
                .get("射击");

        assertThat(shooting)
                .contains("| 弓箭 |")
                .contains("| 弩 |")
                .contains("| 泰瑟枪 |");
    }
}
