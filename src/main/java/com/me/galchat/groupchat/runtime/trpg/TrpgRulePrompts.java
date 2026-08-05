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

    static String skillIndex() {
        return "\n" + CocSkillRuleConstant.KP_SKILL_INDEX + "\n";
    }

    static String investigatorResidentRules() {
        return "\n<investigator-resident-rules>\n"
                + InvestigatorRulePromptConstant.INVESTIGATOR_RESIDENT_RULES
                + "\n</investigator-resident-rules>\n";
    }
}
