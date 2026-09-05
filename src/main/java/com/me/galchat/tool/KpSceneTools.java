package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.trpg.TrpgSceneLifecycleService;
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
            description = """
                    KP决定结束当前主场景或子场景时，必须调用此工具进入结算。
                    结束子场景不会影响父场景。
                    调用后的公开消息只能说明“XXX决定离开了XX”，不得加入后续前往场景的任何内容。
                    """)
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
        return "当前场景已请求结算；结束子场景不会影响父场景。公开消息只能说明‘XXX决定离开了XX’，不得加入后续前往场景的任何内容。";
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP结束场景工具上下文不存在");
        }
        return context.getContext();
    }
}
