package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.TrpgRunLifecycleService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpRunTools {

    private final TrpgRunLifecycleService lifecycleService;

    @Tool(
            name = "finishRun",
            description = "KP确认模组已经跑完并永久关闭当前跑团群聊。调用后仍需回复最终公开收束消息。")
    public String finishRun(ToolContext context) {
        Map<String, Object> values = requireContext(context);
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以结束整个跑团");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long replyStepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || replyStepId == null) {
            throw new UserRequestException("结束跑团工具缺少群聊或回复步骤上下文");
        }
        lifecycleService.requestFinish(
                conversationId, replyStepId);
        return "跑团将在本轮结束后关闭。请向所有调查员给出最终公开收束消息。";
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("结束跑团工具上下文不存在");
        }
        return context.getContext();
    }
}
