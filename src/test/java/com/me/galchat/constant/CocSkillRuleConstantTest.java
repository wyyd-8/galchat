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
}
