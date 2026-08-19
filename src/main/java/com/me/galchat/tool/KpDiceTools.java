package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
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
public class KpDiceTools {

    private final ICocDiceOrchestrationService orchestrationService;

    @Tool(
            name = "requestCheck",
            description = "发起单人属性/技能检定；可为该角色提供多个候选检定项，"
                    + "后端取角色卡中数值最高的一项，只掷一次并直接给出成功或失败。",
            returnDirect = true)
    public KpDiceToolResult requestCheck(
            @ToolParam(description = "检定原因、难度和角色检定项")
            KpDiceRequestDTOs.Check request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.requestCheck(
                kp.conversationId(), kp.runId(), request);
    }

    @Tool(
            name = "requestGroupCheck",
            description = "发起群体属性/技能检定。必须选择群体展示规则："
                    + "任一成功适用于聆听等一人发现即可的检定；"
                    + "全部成功适用于潜行等所有人都必须通过的检定；"
                    + "分离表示分别展示、不计算群体结论，不确定时使用分离。"
                    + "每个角色只能出现一次；若同一角色可用多个检定项，"
                    + "放入该角色的同一个候选列表，后端取最高值且只掷一次。"
                    + "该规则仅供前端展示，不改变后端返回的各角色检定结果。",
            returnDirect = true)
    public KpDiceToolResult requestGroupCheck(
            @ToolParam(description = "群体检定原因、难度、展示规则和角色检定项")
            KpDiceRequestDTOs.GroupCheck request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.requestGroupCheck(
                kp.conversationId(), kp.runId(), request);
    }

    @Tool(
            name = "requestOpposedCheck",
            description = "发起至少两名角色的对抗检定；后端直接给出胜者、平局或全员失败结果。",
            returnDirect = true)
    public KpDiceToolResult requestOpposedCheck(
            @ToolParam(description = "对抗原因、角色检定项和可选平局胜者")
            KpDiceRequestDTOs.Opposed request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.requestOpposedCheck(
                kp.conversationId(), kp.runId(), request);
    }

    @Tool(
            name = "requestSanCheck",
            description = "按角色当前SAN发起理智检定；本工具不自动扣除理智。",
            returnDirect = true)
    public KpDiceToolResult requestSanCheck(
            @ToolParam(description = "理智检定原因和角色名")
            KpDiceRequestDTOs.SanCheck request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.requestSanCheck(
                kp.conversationId(), kp.runId(), request);
    }

    @Tool(
            name = "rollSanLoss",
            description = "根据最近一次理智检定结果选择成功/失败公式，扣除SAN并按规则创建临时疯狂轮。",
            returnDirect = true)
    public KpDiceToolResult rollSanLoss(
            @ToolParam(description = "损失原因以及SAN成功和失败时的表达式")
            KpDiceRequestDTOs.SanLoss request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.rollSanLoss(
                kp.conversationId(), kp.runId(), request);
    }

    @Tool(
            name = "rollDamage",
            description = "在独立掷骰流程中结算已经成立的伤害。"
                    + "公式可把眩晕作为独立加数；后端会另投1D6并与已有眩晕剩余回合取较大值，不计入HP伤害。"
                    + "后端不会自动判断护甲，也不会自动扣除护甲；若KP手动判断护甲适用，必须只对HP伤害部分减甲，例如max(0,(1D3)-2)+眩晕。",
            returnDirect = true)
    public KpDiceToolResult rollDamage(
            @ToolParam(description = "伤害原因、目标角色与表达式")
            KpDiceRequestDTOs.Damage request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.rollDamage(
                kp.conversationId(), kp.runId(), request);
    }

    @Tool(
            name = "rollHealing",
            description = "结算无来源回血，或为最近一次成功的单次检定追加回血轮。"
                    + "急救可解除昏迷和重伤，医学可解除重伤。"
                    + "无特殊情况时，一个大场景内每种恢复生命方法对同一目标只能使用一次。",
            returnDirect = true)
    public KpDiceToolResult rollHealing(
            @ToolParam(description = "回血原因、来源模式、恢复方式、来源角色、目标角色与表达式")
            KpDiceRequestDTOs.Healing request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.rollHealing(
                kp.conversationId(), kp.runId(), request);
    }

    private KpExecutionContext requireKpContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        String actorType = TypeConvertUtils.asString(
                values.get(ChatToolContextConstant.ACTOR_TYPE_KEY));
        if (!GroupChatConstant.ACTOR_KP.equals(actorType)) {
            throw new UserAuthException("只有KP可以调用掷骰工具");
        }
        Long conversationId = TypeConvertUtils.asLong(
                values.get(ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long replyStepId = TypeConvertUtils.asLong(
                values.get(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || replyStepId == null) {
            throw new UserRequestException("KP工具缺少群聊、跑团或回复步骤上下文");
        }
        return new KpExecutionContext(
                conversationId, conversationId, replyStepId);
    }

    private record KpExecutionContext(
            Long conversationId,
            Long runId,
            Long replyStepId) {
    }
}
