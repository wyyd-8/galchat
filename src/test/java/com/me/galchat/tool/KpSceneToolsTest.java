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

class KpSceneToolsTest {

    @Test
    void kpFinishesSceneByToolContextIdentity() throws Exception {
        TrpgSceneLifecycleService service =
                mock(TrpgSceneLifecycleService.class);
        KpSceneTools tools = new KpSceneTools(service);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                8L));

        tools.finishSceneExploration(context);

        verify(service).requestKpFinish(7L, 8L);
        Tool annotation = KpSceneTools.class
                .getMethod("finishSceneExploration", ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isFalse();
    }
}
