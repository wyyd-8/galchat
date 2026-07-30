package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.TrpgChildSceneCommandService;
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
public class KpWaitingInvestigatorTools {

    private final TrpgChildSceneCommandService commandService;

    @Tool(
            name = "resumeWaitingInvestigators",
            description = """
                    等待中的调查员此前已经在子场景中独立行动了一段时间。
                    请根据当前剧情、时间经过、位置关系和队伍行动，选择自然且合适的汇合时机调用本工具。
                    工具使指定调查员从下一轮开始重新参与行动；不要仅因为工具可用就立即调用。
                    """)
    public String resumeWaitingInvestigators(
            @ToolParam(description = "结束等待并从下一轮恢复行动的准确调查员名称列表")
            List<String> investigatorNames,
            ToolContext context) {
        Map<String, Object> values = requireKpContext(context);
        return commandService.resumeWaitingInvestigators(
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant
                                .GROUP_CONVERSATION_ID_KEY)),
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant
                                .GROUP_REPLY_STEP_ID_KEY)),
                investigatorNames);
    }

    private Map<String, Object> requireKpContext(
            ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP等待工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以恢复等待调查员");
        }
        return values;
    }
}
