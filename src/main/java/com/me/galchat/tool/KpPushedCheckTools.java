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
public class KpPushedCheckTools {

    private final ICocDiceOrchestrationService orchestrationService;

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
        return new KpExecutionContext(conversationId, conversationId);
    }

    private record KpExecutionContext(Long conversationId, Long runId) {
    }
}
