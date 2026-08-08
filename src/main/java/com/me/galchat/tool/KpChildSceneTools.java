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
public class KpChildSceneTools {

    private final TrpgChildSceneCommandService commandService;

    @Tool(
            name = "startChildScene",
            description = """
                    当调查员决定分头行动，并且其中一队进入需要独立推进的地点或行动区域时，创建动态子场景。
                    子场景仅分隔行动轮，不会提供更多模组信息；它继承当前大场景的全部模组上下文。
                    如果所有调查员一起行动，应继续停留在主场景中，不推荐调用本工具。
                    调用成功后，当前公开回复需要在原有叙事基础上明确说明哪些调查员去了哪个地点，不要提前描述子场景内部信息。
                    """)
    public String startChildScene(
            @ToolParam(description = "简洁、明确的动态子场景名称，不超过200个字符")
            String childSceneName,
            @ToolParam(description = "进入子场景的准确调查员名称列表，允许选择全部活动调查员")
            List<String> investigatorNames,
            ToolContext context) {
        Map<String, Object> values = requireKpContext(context);
        return commandService.startChildScene(
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant
                                .GROUP_CONVERSATION_ID_KEY)),
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant
                                .GROUP_REPLY_STEP_ID_KEY)),
                childSceneName,
                investigatorNames);
    }

    private Map<String, Object> requireKpContext(
            ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP子场景工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以创建子场景");
        }
        return values;
    }
}
