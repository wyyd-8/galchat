package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.service.impl.GroupConversationService;
import com.me.galchat.service.impl.TrpgInquiryLuckService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpInquiryLuckToolsTest {

    @Test
    void exposesOnlyNarrowInquiryLuckContract() throws Exception {
        TrpgInquiryLuckService luck = mock(TrpgInquiryLuckService.class);
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        KpDiceToolResult expected = mock(KpDiceToolResult.class);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(luck.request(
                conversation, 101L, 301L,
                "有一辆空载出租车经过",
                "CURRENT_INVESTIGATOR_GROUP"))
                .thenReturn(expected);
        KpInquiryLuckTools tools =
                new KpInquiryLuckTools(luck, conversations);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
                ChatToolContextConstant.GROUP_TURN_ID_KEY, 101L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 301L));

        KpDiceToolResult actual = tools.requestInquiryLuck(
                "有一辆空载出租车经过",
                "CURRENT_INVESTIGATOR_GROUP", context);

        assertThat(actual).isSameAs(expected);
        verify(luck).request(
                conversation, 101L, 301L,
                "有一辆空载出租车经过",
                "CURRENT_INVESTIGATOR_GROUP");
        Tool annotation = KpInquiryLuckTools.class.getMethod(
                        "requestInquiryLuck", String.class,
                        String.class, ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isTrue();
        assertThat(DiceRollConstant.KP_STATE_TOOL_NAMES)
                .contains("requestInquiryLuck");
        assertThat(annotation.description())
                .contains("外部偶然")
                .contains("不能决定NPC")
                .contains("不能生成武器");
    }
}
