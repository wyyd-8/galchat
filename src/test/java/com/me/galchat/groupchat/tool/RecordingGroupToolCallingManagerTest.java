package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingGroupToolCallingManagerTest {

    @Test
    void exposesAuthenticatedUserOnlyWhileExecutingGroupTool() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        TransactionTemplate transactionTemplate =
                mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager =
                new RecordingGroupToolCallingManager(
                        delegate, store, transactionTemplate);
        ChatResponse response = mock(ChatResponse.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        Prompt prompt = prompt(Map.of(
                ChatToolContextConstant.USER_ID_KEY, 12L));
        AtomicReference<Integer> observedUserId =
                new AtomicReference<>();
        when(delegate.executeToolCalls(prompt, response))
                .thenAnswer(invocation -> {
                    observedUserId.set(CurrentHolder.getCurrentId());
                    return result;
                });
        CurrentHolder.remove();

        try {
            assertThat(manager.executeToolCalls(prompt, response))
                    .isSameAs(result);

            assertThat(observedUserId).hasValue(12);
            assertThat(CurrentHolder.getCurrentId()).isNull();
        } finally {
            CurrentHolder.remove();
        }
    }

    @Test
    void recordsExecutionAgainstReplyStepFromToolContext() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager = new RecordingGroupToolCallingManager(
                delegate, store, transactionTemplate);
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
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager = new RecordingGroupToolCallingManager(
                delegate, store, transactionTemplate);
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

    @Test
    void diceToolExecutionAndRecordingUseOneTransaction() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager = new RecordingGroupToolCallingManager(
                delegate, store, transactionTemplate);
        Prompt prompt = prompt(Map.of(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));
        ChatResponse response = responseWithCalls("requestCheck");
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(delegate.executeToolCalls(prompt, response)).thenReturn(result);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<org.springframework.transaction.support.TransactionCallback<ToolExecutionResult>>
                        getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));

        assertThat(manager.executeToolCalls(prompt, response)).isSameAs(result);

        var order = inOrder(delegate, store);
        order.verify(delegate).executeToolCalls(prompt, response);
        order.verify(store).saveExecution(41L, response, result);
        verify(transactionTemplate).execute(any());
    }

    @Test
    void investigatorInquiryExecutionAndRecordingUseOneTransaction() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        TransactionTemplate transactionTemplate =
                mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager =
                new RecordingGroupToolCallingManager(
                        delegate, store, transactionTemplate);
        Prompt prompt = prompt(Map.of(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));
        ChatResponse response = responseWithCalls(
                "askKp");
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(delegate.executeToolCalls(prompt, response))
                .thenReturn(result);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<org.springframework.transaction.support.TransactionCallback<ToolExecutionResult>>
                        getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));

        assertThat(manager.executeToolCalls(prompt, response))
                .isSameAs(result);

        var order = inOrder(delegate, store);
        order.verify(delegate).executeToolCalls(prompt, response);
        order.verify(store).saveExecution(41L, response, result);
        verify(transactionTemplate).execute(any());
    }

    @Test
    void rejectsParallelCallsWhenOneIsStateChangingDiceTool() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager = new RecordingGroupToolCallingManager(
                delegate, store, transactionTemplate);
        Prompt prompt = prompt(Map.of(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));

        assertThatThrownBy(() -> manager.executeToolCalls(
                prompt, responseWithCalls("requestCheck", "searchInfo")))
                .hasMessageContaining("只能调用一个掷骰工具");

        verifyNoInteractions(delegate);
    }

    @Test
    void rejectsParallelCallsWhenOneIsClarification() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        RecordingGroupToolCallingManager manager =
                new RecordingGroupToolCallingManager(
                        delegate, mock(GroupToolCallStore.class),
                        mock(TransactionTemplate.class));

        assertThatThrownBy(() -> manager.executeToolCalls(
                prompt(Map.of()), responseWithCalls(
                        "askKp", "searchInfo")))
                .hasMessageContaining("追问工具必须单独调用");

        verifyNoInteractions(delegate);
    }

    @Test
    void rejectsWeaponStateOverwriteAfterFirearmToolInSameReplyStep() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = new GroupToolCallStore(
                mock(com.me.galchat.mapper.GroupChatToolCallMapper.class),
                mock(tools.jackson.databind.ObjectMapper.class),
                mock(com.me.galchat.service.impl
                        .GroupTurnCheckpointService.class)) {
            public boolean hasExecution(
                    Long replyStepId, String toolName) {
                return Long.valueOf(41L).equals(replyStepId)
                        && "requestFirearmAttack".equals(toolName);
            }
        };
        RecordingGroupToolCallingManager manager =
                new RecordingGroupToolCallingManager(
                        delegate, store,
                        mock(TransactionTemplate.class));
        Prompt prompt = prompt(Map.of(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));

        assertThatThrownBy(() -> manager.executeToolCalls(
                prompt, responseWithCalls("updateWeaponState")))
                .hasMessageContaining("枪械攻击工具已自动更新武器状态");

        verifyNoInteractions(delegate);
    }

    @Test
    void combatFinishMarkerIsExecutedButNotRecorded() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        TransactionTemplate transactionTemplate =
                mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager =
                new RecordingGroupToolCallingManager(
                        delegate, store, transactionTemplate);
        Prompt prompt = prompt(Map.of(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));
        ChatResponse response =
                responseWithCalls("markCombatFinished");
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(delegate.executeToolCalls(prompt, response))
                .thenReturn(result);

        assertThat(manager.executeToolCalls(prompt, response))
                .isSameAs(result);

        verify(store, never()).saveExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private Prompt prompt(Map<String, Object> toolContext) {
        return new Prompt("test", DeepSeekChatOptions.builder().toolContext(toolContext).build());
    }

    private ChatResponse responseWithCalls(String... names) {
        List<AssistantMessage.ToolCall> calls = java.util.stream.IntStream
                .range(0, names.length)
                .mapToObj(index -> new AssistantMessage.ToolCall(
                        "call-" + index,
                        "function",
                        names[index],
                        "{}"))
                .toList();
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(calls).build())));
    }
}
