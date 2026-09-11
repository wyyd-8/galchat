package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.service.impl.trpg.TrpgRunLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KpRunToolsTest {

    @Test
    void kpRequestsRunFinishWithoutDirectReturn() throws Exception {
        TrpgRunLifecycleService service =
                mock(TrpgRunLifecycleService.class);
        KpRunTools tools = new KpRunTools(service);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                8L));

        String result = tools.finishRun(context);

        verify(service).requestFinish(7L, 8L);
        assertThat(result)
                .contains("人物后传", "自动生成")
                .doesNotContain("请向所有调查员给出最终公开收束消息");
        Tool annotation = KpRunTools.class
                .getMethod("finishRun", ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isFalse();
    }
}
