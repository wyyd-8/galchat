package com.me.galchat.memory;

import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserChatMemoryTest {

    @Test
    void toPromptMessagesKeepsEmptyToolResultAfterToolCall() {
        UserChatToolCallMapper toolCallMapper = mock(UserChatToolCallMapper.class);
        when(toolCallMapper.selectList(any())).thenReturn(List.of(new UserChatToolCall()
                .setUserMessageId(1L)
                .setStepNo(1)
                .setToolCallId("call_1")
                .setToolName("searchInfo")
                .setToolArguments("{\"query\":\"天喉广场相关信息\"}")
                .setToolResult("")));

        UserChatMemory memory = UserChatMemory.builder(mock(UserChatHistoryMapper.class))
                .toolCallMapper(toolCallMapper)
                .includeToolCalls(true)
                .build();

        List<Message> messages = memory.toPromptMessages(List.of(
                new UserChatHistory()
                        .setId(1L)
                        .setType(MessageType.USER.getValue())
                        .setContent("调用searchInfo，查询天喉广场相关信息，返回工具结果"),
                new UserChatHistory()
                        .setId(2L)
                        .setUserMessageId(1L)
                        .setStepNo(2)
                        .setType(MessageType.ASSISTANT.getValue())
                        .setContent("工具已调用完毕")));

        assertThat(messages).hasSize(4);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) messages.get(1)).getToolCalls()).hasSize(1);
        assertThat(messages.get(2)).isInstanceOf(ToolResponseMessage.class);

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) messages.get(2);
        assertThat(toolResponseMessage.getResponses()).singleElement()
                .satisfies(response -> assertThat(response.responseData()).isEqualTo(""));
    }
}
