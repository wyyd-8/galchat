package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.TrpgSceneLifecycleService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpSceneTools {

    private final TrpgSceneLifecycleService lifecycleService;

    @Tool(
            name = "finishSceneExploration",
            description = "KP直接结束当前场景探索并进入结算。调用后仍需回复具体公开消息。")
    public String finishSceneExploration(ToolContext context) {
        Map<String, Object> values = requireContext(context);
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以直接结束场景探索");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long replyStepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || replyStepId == null) {
            throw new UserRequestException("KP结束场景工具缺少群聊或回复步骤上下文");
        }
        lifecycleService.requestKpFinish(conversationId, replyStepId);
        return "当前场景已请求结算。请向调查员公开说明场景如何收束。";
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP结束场景工具上下文不存在");
        }
        return context.getContext();
    }
}
