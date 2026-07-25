package com.me.galchat.groupchat.tool;

import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupToolCallStoreTest {

    @Test
    void savesToolCallAndItsResponseInOneModelStep() {
        GroupChatToolCallMapper mapper = mock(GroupChatToolCallMapper.class);
        GroupToolCallStore store = new GroupToolCallStore(mapper);
        when(mapper.nextToolStepNo(41L)).thenReturn(3);
        doAnswer(invocation -> {
            invocation.<GroupChatToolCall>getArgument(0).setId(9L);
            return 1;
        }).when(mapper).insert(any(GroupChatToolCall.class));
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "searchInfo", "{\"query\":\"旧宅\"}")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "searchInfo", "旧宅位于河边")))
                .build();
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(result.conversationHistory()).thenReturn(List.of(toolResponse));

        store.saveExecution(41L, response, result);

        ArgumentCaptor<GroupChatToolCall> inserted = ArgumentCaptor.forClass(GroupChatToolCall.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue())
                .extracting(
                        GroupChatToolCall::getReplyStepId,
                        GroupChatToolCall::getToolStepNo,
                        GroupChatToolCall::getToolCallId,
                        GroupChatToolCall::getToolName,
                        GroupChatToolCall::getToolArguments)
                .containsExactly(41L, 3, "call-1", "searchInfo", "{\"query\":\"旧宅\"}");
        verify(mapper).updateById(org.mockito.ArgumentMatchers.argThat((GroupChatToolCall call) ->
                Long.valueOf(9L).equals(call.getId())
                        && "旧宅位于河边".equals(call.getToolResult())));
    }

    @Test
    void bindsDiceSummaryByReplyStepAndToolCallId() {
        GroupChatToolCallMapper mapper = mock(GroupChatToolCallMapper.class);
        GroupToolCallStore store = new GroupToolCallStore(mapper);

        store.bindDiceSummary(41L, "call-1", 501L);

        verify(mapper).bindDiceSummary(41L, "call-1", 501L);
    }
}
