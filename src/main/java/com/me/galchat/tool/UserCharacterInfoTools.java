package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class UserCharacterInfoTools {

    private final IUserCharacterInfoService userCharacterInfoService;

    @Tool(description = """
            工具描述：
            向当前角色的 user_info_prompt 追加一条用户信息。
            当用户在对话中告诉当前角色个人信息、偏好、称呼、关系设定、重要习惯或希望角色以后记住的内容时，必须调用本工具。
            使用流程：
            1. 仅提炼用户表达的信息，不要记录普通寒暄、一次性情绪、临时动作或不确定推测。
            2. content 传入一条简洁自然的中文记录，保留必要细节，避免写成对话回复。
            3. 本工具会把 content 追加到现有 user_info_prompt 后面，不会覆盖用户手动编辑的已有内容。
            注意事项：
            不要记录敏感隐私；不要重复追加已经存在或语义相同的内容。
            """)
    public void appendUserInfoPrompt(@ToolParam(description = "要追加到 user_info_prompt 的长期用户信息") String content,
                                       ToolContext context) {
        if (!StringUtils.hasText(content) || context == null || context.getContext() == null) {
            return;
        }

        Map<String, Object> map = context.getContext();
        Long userWorldId = TypeConvertUtils.asLong(map.get(ChatToolContextConstant.USER_WORLD_ID_KEY));
        Long characterId = TypeConvertUtils.asLong(map.get(ChatToolContextConstant.CHARACTER_ID_KEY));
        if (userWorldId == null || characterId == null) {
            return;
        }

        userCharacterInfoService.appendUserInfoPrompt(userWorldId, characterId, content);
        return;
    }
}
