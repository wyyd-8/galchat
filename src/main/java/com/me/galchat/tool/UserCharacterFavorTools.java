package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.FavorConstant;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class UserCharacterFavorTools {

    private final IUserCharacterInfoService userCharacterInfoService;

    @Tool(description = """
            工具描述：
            调整当前角色对用户的好感值。
            使用流程：
            1. 当当前对话中用户的行为、表达或选择明确会影响角色好感时，调用本工具。
            2. favorChange 传入本次期望变化值，正数表示增加好感，负数表示降低好感。
            3. 该方法没有返回值。
            注意事项：
            仅在当前对话内容确实触发好感变化时调用，不要因为普通寒暄或无明显态度变化的内容调用。
            """)
    public void updateFavorValue(@ToolParam(description = "本次好感变化值，正数增加，负数减少") Integer favorChange,
                                   ToolContext context) {
        if (context == null || context.getContext() == null) {
            return;
        }

        Map<String, Object> map = context.getContext();
        Long userWorldId = TypeConvertUtils.asLong(map.get(ChatToolContextConstant.USER_WORLD_ID_KEY));
        Long characterId = TypeConvertUtils.asLong(map.get(ChatToolContextConstant.CHARACTER_ID_KEY));
        Long userMessageId = TypeConvertUtils.asLong(map.get(ChatToolContextConstant.USER_MESSAGE_ID_KEY));
        String favorSystemStatus = TypeConvertUtils.asString(map.get(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY));
        if (userWorldId == null || characterId == null || userMessageId == null) {
            return;
        }

        userCharacterInfoService.updateFavorValue(userWorldId, characterId,
                applyFavorSystemCoefficient(favorChange, favorSystemStatus), userMessageId);
    }

    private Integer applyFavorSystemCoefficient(Integer favorChange, String favorSystemStatus) {
        if (favorChange == null || favorChange <= 0) {
            return favorChange;
        }

        return Math.toIntExact(Math.round(favorChange * favorCoefficient(favorSystemStatus)));
    }

    private double favorCoefficient(String favorSystemStatus) {
        if (FavorConstant.EASY_STATUS.equalsIgnoreCase(favorSystemStatus)) {
            return FavorConstant.EASY_COEFFICIENT;
        }
        if (FavorConstant.HARD_STATUS.equalsIgnoreCase(favorSystemStatus)) {
            return FavorConstant.HARD_COEFFICIENT;
        }
        return FavorConstant.DEFAULT_COEFFICIENT;
    }
}
