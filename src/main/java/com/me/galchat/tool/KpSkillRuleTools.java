package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.CocSkillRuleConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.utils.TypeConvertUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class KpSkillRuleTools {

    @Tool(
            name = "readSkillRules",
            description = "按一个或多个KP上下文技能清单中的准确名称读取详细说明。只提供技能名称，不要提供查询原因、场景或角色信息。")
    public Map<String, String> readSkillRules(
            @ToolParam(description = "KP上下文技能清单中的准确技能名称列表")
            List<String> skillNames,
            ToolContext context) {
        requireKp(context);
        List<String> requestedNames = normalize(skillNames);
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (String requestedName : requestedNames) {
            String description = CocSkillRuleConstant
                    .SKILL_RULES_BY_NAME.get(requestedName);
            if (description == null) {
                throw new UserRequestException(
                        "技能“" + requestedName
                                + "”不在KP上下文技能清单中");
            }
            descriptions.put(requestedName, description);
        }
        return Collections.unmodifiableMap(descriptions);
    }

    private List<String> normalize(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            throw new UserRequestException("技能名称列表不能为空");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String skillName : skillNames) {
            if (!StringUtils.hasText(skillName)) {
                throw new UserRequestException("技能名称不能为空");
            }
            normalized.add(skillName.trim());
        }
        return List.copyOf(normalized);
    }

    private void requireKp(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP技能规则工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以检索技能规则");
        }
    }
}
