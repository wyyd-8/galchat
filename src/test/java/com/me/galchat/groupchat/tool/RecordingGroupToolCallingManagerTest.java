package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingGroupToolCallingManagerTest {

    @Test
    void recordsExecutionAgainstReplyStepFromToolContext() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        RecordingGroupToolCallingManager manager = new RecordingGroupToolCallingManager(delegate, store);
        ChatResponse response = mock(ChatResponse.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        Prompt prompt = prompt(Map.of(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(result);

        ToolExecutionResult actual = manager.executeToolCalls(prompt, response);

        assertThat(actual).isSameAs(result);
        verify(store).saveExecution(41L, response, result);
    }

    @Test
    void delegatesWithoutRecordingWhenGroupStepIsAbsent() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        RecordingGroupToolCallingManager manager = new RecordingGroupToolCallingManager(delegate, store);
        ChatResponse response = mock(ChatResponse.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        Prompt prompt = prompt(Map.of());
        when(delegate.executeToolCalls(prompt, response)).thenReturn(result);

        assertThat(manager.executeToolCalls(prompt, response)).isSameAs(result);

        verify(store, never()).saveExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private Prompt prompt(Map<String, Object> toolContext) {
        return new Prompt("test", DeepSeekChatOptions.builder().toolContext(toolContext).build());
    }
}
