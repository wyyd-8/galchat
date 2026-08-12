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
public class InvestigatorSceneTools {

    private final TrpgSceneLifecycleService lifecycleService;

    @Tool(
            name = "endSceneExploration",
            description = """
                    当前调查员确认不再执行场景内行动并结束自己的探索。调用后仍需回复公开消息。
                    调查员处于子场景时，也可以调用此工具结束当前子场景的探索，不会影响父场景。
                    """)
    public String endSceneExploration(ToolContext context) {
        Map<String, Object> values = requireContext(context);
        if (!GroupChatConstant.ACTOR_CHARACTER.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有调查员可以结束自己的场景探索");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long replyStepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        Long characterId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.CHARACTER_ID_KEY));
        if (conversationId == null || replyStepId == null
                || characterId == null) {
            throw new UserRequestException("结束探索工具缺少群聊、回复步骤或调查员上下文");
        }
        boolean allReady = lifecycleService.requestInvestigatorFinish(
                conversationId, replyStepId, characterId);
        return allReady
                ? "所有调查员均已结束探索，当前场景将进入结算。请用公开消息确认你的行动结束。"
                : "你的结束探索意向已记录。请用公开消息说明你已完成当前场景的行动。";
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("结束探索工具上下文不存在");
        }
        return context.getContext();
    }
}
