package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.trpg.TrpgInquiryLuckService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpInquiryLuckTools {

    private final TrpgInquiryLuckService luckService;
    private final GroupConversationService conversationService;

    @Tool(
            name = "requestInquiryLuck",
            description = "仅在回答调查员询问时，为尚未确定、成功与失败都合理的外部偶然事件发起一次幸运检定。"
                    + "不能用于固定或可见事实、搜索和行动结果、隐藏线索、容器内部，不能决定NPC反应，"
                    + "也不能生成武器、贵重资源或关键线索。成功只确立所写事件存在，不保证调查员随后能利用它。",
            returnDirect = true)
    public KpDiceToolResult requestInquiryLuck(
            @ToolParam(description = "检定成功时成立的一个具体、肯定、可观察事实")
            String favorableEvent,
            @ToolParam(
                    description = "REQUESTER表示仅涉及询问者；CURRENT_INVESTIGATOR_GROUP表示影响当前在场调查员的公共环境")
            String scope,
            ToolContext context) {
        Map<String, Object> values = requireContext(context);
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以调用询问幸运工具");
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
                    "询问幸运工具缺少群聊、行动轮或回复步骤上下文");
        }
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        return luckService.request(
                conversation, turnId, replyStepId,
                favorableEvent, scope);
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("询问幸运工具上下文不存在");
        }
        return context.getContext();
    }
}
