package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.service.impl.TrpgSceneLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InvestigatorSceneToolsTest {

    @Test
    void investigatorFinishesByToolContextIdentity() throws Exception {
        TrpgSceneLifecycleService service =
                mock(TrpgSceneLifecycleService.class);
        InvestigatorSceneTools tools =
                new InvestigatorSceneTools(service);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_CHARACTER,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                8L,
                ChatToolContextConstant.CHARACTER_ID_KEY,
                9L));

        tools.endSceneExploration(context);

        verify(service).requestInvestigatorFinish(7L, 8L, 9L);
        Tool annotation = InvestigatorSceneTools.class
                .getMethod("endSceneExploration", ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isFalse();
    }
}
