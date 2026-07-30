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
            description = "发起单人或群体属性/技能检定；后端读取角色卡目标值并直接给出成功或失败。",
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
            name = "requestOpposedCheck",
            description = "发起至少两名角色的对抗检定；后端直接给出胜者或平局。",
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
            name = "requestPushedCheck",
            description = "为最近一次兼容且失败的普通检定追加孤注一掷轮。",
            returnDirect = true)
    public KpDiceToolResult requestPushedCheck(
            @ToolParam(description = "孤注一掷原因和需要重掷的角色名")
            KpDiceRequestDTOs.Pushed request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.requestPushedCheck(
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
            description = "结算独立伤害，或为最近一次成功的攻击/对抗检定追加伤害轮。",
            returnDirect = true)
    public KpDiceToolResult rollDamage(
            @ToolParam(description = "伤害原因、来源模式、来源角色、目标角色与表达式")
            KpDiceRequestDTOs.Damage request,
            ToolContext context) {
        KpExecutionContext kp = requireKpContext(context);
        return orchestrationService.rollDamage(
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
