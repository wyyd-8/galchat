package com.me.galchat.tool;

import com.me.galchat.service.impl.trpg.TrpgCombatStateService;
import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpCombatStateDTOs;
import com.me.galchat.domain.dto.KpQuickNpcDTOs;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.trpg.TrpgCombatLifecycleService;
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
    private final TrpgCombatStateService
            combatStateService;

    @Tool(
            name = "startCombat",
            description = "从当前场景发起战斗，选择准确人物卡名称和先攻模式。"
                    + "战斗只会在当前KP步骤完整成功后激活；工具返回后当前步骤仍是战斗前的场景步骤，战斗尚未激活。"
                    + "公开消息只能确认被登记的参战者，不得描述先攻顺序、战斗轮或任何角色的新行动，也不得替未参战角色决定移动、旁观、逃跑或协助。"
                    + "确认参战者后立即结束回复，战斗环境和首个行动留给后续独立步骤。")
    public TrpgCombatLifecycleService.StartResult startCombat(
            @ToolParam(description = "已有准确人物卡的参战者名称列表；可为空，和quickNpcs合计至少两名")
            List<String> participantNames,
            @ToolParam(
                    description = "临时 NPC 列表；每项填写唯一名称、强度档位和典型武器，可与现有人物卡混用",
                    required = false)
            List<KpQuickNpcDTOs.Spec> quickNpcs,
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
                participantNames, quickNpcs,
                orderMode, declaredAttackerNames);
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

    @Tool(
            name = "updateCombatStates",
            description = "批量更新当前战斗参战人物卡的KP可控状态。"
                    + "只允许修改是否处于掩护、是否仍需因寻找掩护失去下一主动位，以及钳制者。"
                    + "设置准确人物卡名称表示施加钳制，设置空字符串解除钳制；成功挣脱、钳制者主动松开、无法继续压制或受到重伤时应解除。"
                    + "被眩晕剩余回合和本轮已被近战攻击由后端维护，不能通过本工具修改。"
                    + "所有值都是覆盖写入；重复提交相同值不会重复产生效果。")
    public KpCombatStateDTOs.Result updateCombatStates(
            @ToolParam(description = "按准确人物卡名称提交的一项或多项战斗状态覆盖")
            KpCombatStateDTOs.Update update,
            ToolContext context) {
        KpContext kp = requireKp(context);
        return combatStateService.updateCombatStates(
                kp.conversationId(), update);
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
