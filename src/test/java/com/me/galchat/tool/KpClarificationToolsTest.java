package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.service.impl.GroupConversationService;
import com.me.galchat.service.impl.TrpgStepInteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpClarificationToolsTest {

    @Test
    void explicitToolUsesTrustedKpContextAndReturnsDirectly()
            throws Exception {
        TrpgStepInteractionService interactions =
                mock(TrpgStepInteractionService.class);
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        var expected = new TrpgStepInteractionService.InteractionRequest(
                301L, 201L, "KP_CLARIFICATION", 1,
                new com.me.galchat.groupchat.runtime.GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L),
                32L, "你要检查抽屉还是桌面？", "METHOD");
        when(interactions.askForClarification(
                conversation, 101L, 201L,
                "INDIVIDUAL", "陈默",
                "你要检查抽屉还是桌面？", "METHOD"))
                .thenReturn(expected);
        KpClarificationTools tools = new KpClarificationTools(
                interactions, conversations);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
                ChatToolContextConstant.GROUP_TURN_ID_KEY, 101L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 201L));

        var actual = tools.askForClarification(
                "INDIVIDUAL", "陈默",
                "你要检查抽屉还是桌面？", "METHOD", context);

        assertThat(actual).isSameAs(expected);
        verify(interactions).askForClarification(
                conversation, 101L, 201L,
                "INDIVIDUAL", "陈默",
                "你要检查抽屉还是桌面？", "METHOD");
        Tool annotation = KpClarificationTools.class.getMethod(
                        "askForClarification",
                        String.class, String.class, String.class,
                        String.class, ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isTrue();
        assertThat(annotation.description())
                .contains("不确定是否需要追问时不要调用")
                .contains("已经明确理解风险时不得重复确认");
    }
}
