package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.trpg.TrpgStepInteractionService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpClarificationTools {

    private final TrpgStepInteractionService interactionService;
    private final GroupConversationService conversationService;

    @Tool(
            name = "askForClarification",
            description = "KP在裁定前向一名调查员公开追问一个必要问题。"
                    + "仅在目标、方法、对象、选择或尚未被明确理解的重大风险会实质影响裁定时调用；"
                    + "不确定是否需要追问时不要调用，已经明确理解风险时不得重复确认。"
                    + "TEAM表示团队共同危险决定，系统只向真人玩家确认。",
            returnDirect = true)
    public TrpgStepInteractionService.InteractionRequest
            askForClarification(
            @ToolParam(description = "INDIVIDUAL或TEAM")
            String scope,
            @ToolParam(
                    required = false,
                    description = "INDIVIDUAL时必填：调查员准确人物卡名称；TEAM时省略")
            String investigatorName,
            @ToolParam(description = "一个简短、公开、可直接回答的问题")
            String question,
            @ToolParam(
                    description = "GOAL、METHOD、TARGET、RISK或CHOICE")
            String reasonType,
            ToolContext context) {
        Map<String, Object> values = requireContext(context);
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以发起追问");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long turnId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_TURN_ID_KEY));
        Long replyStepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || turnId == null
                || replyStepId == null) {
            throw new UserRequestException(
                    "追问工具缺少群聊、行动轮或回复步骤上下文");
        }
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        return interactionService.askForClarification(
                conversation, turnId, replyStepId,
                scope, investigatorName, question, reasonType);
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("追问工具上下文不存在");
        }
        return context.getContext();
    }
}
