package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.TrpgSceneSelectionService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpSceneSelectionTools {

    private final TrpgSceneSelectionService selectionService;

    @Tool(
            name = "publishExplorationScenes",
            description = "KP在选景阶段公布本轮可探索地点，并可同时将当前时间推进到未来；首次选景必须设置时间。",
            returnDirect = true)
    public TrpgSceneSelectionService.SceneOptionsResult
            publishExplorationScenes(
            @ToolParam(description = "可探索地点的准确名称列表")
            List<String> locationNames,
            @ToolParam(
                    required = false,
                    description = "可选目标天数；首次选景必填，后续省略表示保持当前时间")
            Integer targetDay,
            @ToolParam(
                    required = false,
                    description = "可选目标时段：DAWN、MORNING、NOON、AFTERNOON、EVENING或LATE_NIGHT；必须与目标天数同时提供")
            String targetPeriod,
            ToolContext context) {
        Map<String, Object> values = requireContext(context);
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以公布选景地点");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long replyStepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        Long turnId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_TURN_ID_KEY));
        if (conversationId == null || turnId == null
                || replyStepId == null) {
            throw new UserRequestException(
                    "选景工具缺少群聊、行动轮或回复步骤上下文");
        }
        return selectionService.publishOptions(
                conversationId, turnId, replyStepId,
                locationNames, targetDay, targetPeriod);
    }

    private Map<String, Object> requireContext(
            ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("选景工具上下文不存在");
        }
        return context.getContext();
    }
}
