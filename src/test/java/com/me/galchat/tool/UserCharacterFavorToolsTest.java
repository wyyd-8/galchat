package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.FavorBindingType;
import com.me.galchat.service.IUserCharacterInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserCharacterFavorToolsTest {

    @Test
    void bindsSingleChatFavorChangeToUserMessage() {
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        UserCharacterFavorTools tools = new UserCharacterFavorTools(characterService);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.USER_WORLD_ID_KEY, 1L,
                ChatToolContextConstant.CHARACTER_ID_KEY, 11L,
                ChatToolContextConstant.USER_MESSAGE_ID_KEY, 91L));

        tools.updateFavorValue(-2, context);

        verify(characterService).updateFavorValue(
                1L, 11L, -2, FavorBindingType.SINGLE_MESSAGE, 91L);
    }

    @Test
    void bindsGroupFavorChangeToReplyStep() {
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        UserCharacterFavorTools tools = new UserCharacterFavorTools(characterService);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.USER_WORLD_ID_KEY, 1L,
                ChatToolContextConstant.CHARACTER_ID_KEY, 11L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));

        tools.updateFavorValue(-2, context);

        verify(characterService).updateFavorValue(
                1L, 11L, -2, FavorBindingType.GROUP_REPLY_STEP, 41L);
    }
}
