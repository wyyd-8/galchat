package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.TrpgSceneSelectionService;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class TrpgSceneSelectionTools {

    private final TrpgSceneSelectionService selectionService;

    @Tool(
            name = "selectExplorationScene",
            description = "在选景阶段按KP公布的选项编号选择地点；只传编号。",
            returnDirect = true)
    public TrpgSceneSelectionService.SceneChoiceResult
            selectExplorationScene(
            @ToolParam(description = "地点选项编号，例如1或2")
            String optionNo,
            ToolContext context) {
        Map<String, Object> values = requireContext(context);
        if (!GroupChatConstant.ACTOR_CHARACTER.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有调查员可以选择探索场景");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long actorId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.ACTOR_ID_KEY));
        Long turnId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_TURN_ID_KEY));
        if (conversationId == null || turnId == null
                || actorId == null) {
            throw new UserRequestException(
                    "选景工具缺少群聊、行动轮或调查员上下文");
        }
        return selectionService.selectOption(
                conversationId, turnId,
                new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER,
                        actorId),
                optionNo);
    }

    private Map<String, Object> requireContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("选景工具上下文不存在");
        }
        return context.getContext();
    }
}
