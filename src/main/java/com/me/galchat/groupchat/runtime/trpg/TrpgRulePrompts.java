package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.CocSkillRuleConstant;
import com.me.galchat.constant.InvestigatorRulePromptConstant;
import com.me.galchat.constant.TrpgRulePromptConstant;

final class TrpgRulePrompts {

    private TrpgRulePrompts() {
    }

    static String residentRules() {
        return "\n<kp-resident-rules>\n"
                + TrpgRulePromptConstant.KP_RESIDENT_RULES
                + "\n</kp-resident-rules>\n";
    }

    static String combatRules() {
        return "\n<kp-combat-rules>\n"
                + TrpgRulePromptConstant.KP_COMBAT_RULES
                + "\n</kp-combat-rules>\n";
    }

    static String combatActionReference() {
        return "\n<kp-combat-action-reference>\n"
                + TrpgRulePromptConstant.KP_COMBAT_ACTION_REFERENCE
                + "\n</kp-combat-action-reference>\n";
    }

    static String skillIndex() {
        return "\n" + CocSkillRuleConstant.KP_SKILL_INDEX + "\n";
    }

    static String investigatorResidentRules() {
        return "\n<investigator-resident-rules>\n"
                + InvestigatorRulePromptConstant.INVESTIGATOR_RESIDENT_RULES
                + "\n</investigator-resident-rules>\n";
    }

    static String investigatorCombatReference() {
        return "\n<investigator-combat-reference>\n"
                + InvestigatorRulePromptConstant
                .INVESTIGATOR_COMBAT_REFERENCE
                + "\n</investigator-combat-reference>\n";
    }

    static String investigatorThinkingModeRules() {
        return InvestigatorRulePromptConstant.INVESTIGATOR_THINKING_MODE_RULES;
    }
}
