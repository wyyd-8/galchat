package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.TrpgCombatLifecycleService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpCombatTools {

    private final TrpgCombatLifecycleService combatLifecycleService;

    @Tool(
            name = "startCombat",
            description = "从当前场景发起战斗，选择准确人物卡名称和先攻模式。战斗只会在当前KP步骤完整成功后激活。")
    public TrpgCombatLifecycleService.StartResult startCombat(
            @ToolParam(description = "准确的人物卡名称列表，至少两名")
            List<String> participantNames,
            @ToolParam(description = "DEX，或仅第一轮已提前声明攻击的调查员优先的INVESTIGATORS_FIRST")
            String orderMode,
            @ToolParam(
                    description = "仅用于INVESTIGATORS_FIRST：提前声明攻击的调查员准确人物卡名称；未声明者不要加入",
                    required = false)
            List<String> declaredAttackerNames,
            ToolContext context) {
        KpContext kp = requireKp(context);
        return combatLifecycleService.requestStart(
                kp.conversationId(), kp.replyStepId(),
                participantNames, orderMode, declaredAttackerNames);
    }

    @Tool(
            name = "markCombatFinished",
            description = "在战斗裁定中标记本场战斗应在当前裁定完整输出后结束。请在没有剩余检定或掷骰需求后调用；调用后必须继续输出完整裁定和收束。")
    public TrpgCombatLifecycleService.MarkFinishedResult
            markCombatFinished(ToolContext context) {
        KpContext kp = requireKp(context);
        return combatLifecycleService.requestFinish(
                kp.conversationId(), kp.replyStepId());
    }

    private KpContext requireKp(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以调用战斗工具");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long stepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || stepId == null) {
            throw new UserRequestException("KP战斗工具上下文不完整");
        }
        return new KpContext(conversationId, stepId);
    }

    private record KpContext(Long conversationId, Long replyStepId) {
    }
}
