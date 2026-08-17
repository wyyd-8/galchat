package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpMeleeRequestDTOs;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.ICocDiceOrchestrationService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpMeleeTools {

    private final ICocDiceOrchestrationService orchestrationService;

    @Tool(
            name = DiceRollConstant.TOOL_REQUEST_MELEE_ATTACK,
            description = "结算一次普通近战攻击。后端自动进行攻击与闪避/反击检定、按近战同等级规则确定胜者，并自动生成普通、最大或贯穿伤害；NONE仍要求攻击检定成功。远程武器会在本次近战中临时替换为枪托棍棒。",
            returnDirect = true)
    public KpDiceToolResult requestMeleeAttack(
            @ToolParam(description = "攻击者整体参数和防守者整体参数")
            KpMeleeRequestDTOs.Attack request,
            ToolContext context) {
        KpContext kp = requireKp(context);
        return orchestrationService.requestMeleeAttack(
                kp.conversationId(), kp.conversationId(), request);
    }

    private KpContext requireKp(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(TypeConvertUtils.asString(
                values.get(ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以调用近战裁定工具");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long stepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || stepId == null) {
            throw new UserRequestException("KP近战工具上下文不完整");
        }
        return new KpContext(conversationId);
    }

    private record KpContext(Long conversationId) {
    }
}
