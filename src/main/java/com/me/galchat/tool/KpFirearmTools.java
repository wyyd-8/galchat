package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpFirearmRequestDTOs;
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
public class KpFirearmTools {

    private final ICocDiceOrchestrationService orchestrationService;

    @Tool(
            name = DiceRollConstant.TOOL_REQUEST_FIREARM_ATTACK,
            description = "一次性结算一名角色本主动位声明的全部枪械攻击。"
                    + "必须按顺序提交所有目标及分配子弹；后端自动读取目标掩护和体格、叠加结构化的射手/目标高速移动与射击姿势受限惩罚，再与基础修正及射击模式惩罚统一抵消和升级难度。"
                    + "后端还会自动预分配弹药、拆分检定组、处理故障截断，并按每发命中分别扣除目标人物卡护甲后生成伤害轮。"
                    + "武器伤害含独立眩晕加数时，每个有效命中目标另投一次1D6写入眩晕回合，不计入HP。"
                    + "基础奖惩骰不得重复包含掩护、高速移动、小型目标、射击姿势受限、手枪连射、半自动或全自动分组自身的惩罚。",
            returnDirect = true)
    public KpDiceToolResult requestFirearmAttack(
            @ToolParam(description = "开火角色、武器、射击方式、大失败故障选择和按顺序声明的全部目标")
            KpFirearmRequestDTOs.Attack request,
            ToolContext context) {
        KpContext kp = requireKp(context);
        return orchestrationService.requestFirearmAttack(
                kp.conversationId(), kp.conversationId(), request);
    }

    private KpContext requireKp(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(TypeConvertUtils.asString(
                values.get(ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以调用枪械裁定工具");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long stepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || stepId == null) {
            throw new UserRequestException("KP枪械工具上下文不完整");
        }
        return new KpContext(conversationId);
    }

    private record KpContext(Long conversationId) {
    }
}
