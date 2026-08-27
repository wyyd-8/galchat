package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.service.impl.GroupConversationService;
import com.me.galchat.service.impl.TrpgStepInteractionService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class InvestigatorKpInquiryTools {

    private final TrpgStepInteractionService interactionService;
    private final GroupConversationService conversationService;

    @Tool(
            name = "askKp",
            description = "调查员在正式声明行动前，向KP公开询问一个会实质影响行动选择的必要事实。"
                    + "只能确认已经公开但有歧义的事实，或请求描述调查员原地即可直接看见、听见或已知的可见信息；"
                    + "不能用于搜索、移动、接触物体、与NPC交谈、判断行动成败、索取策略或隐藏线索。"
                    + "调查员不要请求幸运检定；是否需要幸运由KP决定。信息已经足够时不要调用。",
            returnDirect = true)
    public TrpgStepInteractionService.InteractionRequest askKp(
            @ToolParam(
                    description = "CONFIRM_PUBLIC_FACT或DESCRIBE_VISIBLE_INFORMATION")
            String inquiryType,
            @ToolParam(description = "一个具体、简短、可由KP直接回答的公开问题")
            String question,
            ToolContext context) {
        Map<String, Object> values = requireContext(context);
        String actorType = TypeConvertUtils.asString(values.get(
                ChatToolContextConstant.ACTOR_TYPE_KEY));
        Long actorId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.ACTOR_ID_KEY));
        if (!GroupChatConstant.ACTOR_CHARACTER.equals(actorType)
                || actorId == null) {
            throw new UserAuthException("只有调查员Agent可以询问KP");
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
                    "询问工具缺少群聊、行动轮或回复步骤上下文");
        }
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        return interactionService.askKp(
                conversation, turnId, replyStepId,
                new GroupActorRef(actorType, actorId),
                inquiryType, question);
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("询问工具上下文不存在");
        }
        return context.getContext();
    }
}
