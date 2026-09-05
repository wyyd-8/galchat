package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.trpg.TrpgStepInteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestigatorKpInquiryToolsTest {

    @Test
    void asksKpWithTrustedInvestigatorContextAndReturnsDirectly()
            throws Exception {
        TrpgStepInteractionService interactions =
                mock(TrpgStepInteractionService.class);
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        var expected = new TrpgStepInteractionService.InteractionRequest(
                301L, 201L,
                TrpgStepInteractionService.INVESTIGATOR_KP_INQUIRY,
                1, new GroupActorRef(GroupChatConstant.ACTOR_KP, null),
                null, "门是否仍然开着？", "CONFIRM_PUBLIC_FACT");
        when(interactions.askKp(
                conversation, 101L, 201L,
                new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L),
                "CONFIRM_PUBLIC_FACT", "门是否仍然开着？"))
                .thenReturn(expected);
        InvestigatorKpInquiryTools tools =
                new InvestigatorKpInquiryTools(
                        interactions, conversations);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_CHARACTER,
                ChatToolContextConstant.ACTOR_ID_KEY, 9L,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
                ChatToolContextConstant.GROUP_TURN_ID_KEY, 101L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 201L));

        var actual = tools.askKp(
                "CONFIRM_PUBLIC_FACT", "门是否仍然开着？", context);

        assertThat(actual).isSameAs(expected);
        verify(interactions).askKp(
                conversation, 101L, 201L,
                new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L),
                "CONFIRM_PUBLIC_FACT", "门是否仍然开着？");
        Tool annotation = InvestigatorKpInquiryTools.class.getMethod(
                        "askKp", String.class, String.class,
                        ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isTrue();
        assertThat(annotation.description())
                .contains("可见")
                .contains("不能用于搜索")
                .contains("不要请求幸运检定");
    }

    @Test
    void rejectsKpActorEvenIfToolIsAccidentallyRegistered() {
        InvestigatorKpInquiryTools tools =
                new InvestigatorKpInquiryTools(
                        mock(TrpgStepInteractionService.class),
                        mock(GroupConversationService.class));
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
                ChatToolContextConstant.GROUP_TURN_ID_KEY, 101L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 201L));

        assertThatThrownBy(() -> tools.askKp(
                "CONFIRM_PUBLIC_FACT", "门是否仍然开着？", context))
                .hasMessageContaining("调查员");
    }
}
