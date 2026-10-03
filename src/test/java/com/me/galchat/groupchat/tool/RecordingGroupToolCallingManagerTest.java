package com.me.galchat.groupchat.tool;

import com.me.galchat.service.impl.group.GroupTurnCheckpointService;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rejectsConflictingChildSceneBatchBeforeExecutingAnyTool(boolean sameDestination) {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        var manager = new RecordingGroupToolCallingManager(delegate, mock(GroupToolCallStore.class),
                mock(TransactionTemplate.class));
        var response = childBatch(sameDestination ? " 钟楼 " : "地下室", sameDestination ? "艾琳" : " 亨利 ");
        assertThatThrownBy(() -> manager.executeToolCalls(prompt(Map.of()), response))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class);
        verifyNoInteractions(delegate);
    }

    @Test
    void permitsDisjointChildScenesInOneResponse() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        var manager = new RecordingGroupToolCallingManager(delegate, mock(GroupToolCallStore.class),
                mock(TransactionTemplate.class));
        var response = childBatch("地下室", "艾琳");
        var prompt = prompt(Map.of());
        var result = mock(ToolExecutionResult.class);
        when(delegate.executeToolCalls(prompt, response)).thenReturn(result);
        assertThat(manager.executeToolCalls(prompt, response)).isSameAs(result);
    }

    private ChatResponse childBatch(String secondScene, String secondInvestigator) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("first", "function", "startChildScene",
                                "{\"childSceneName\":\"钟楼\",\"investigatorNames\":[\"亨利\"]}"),
                        new AssistantMessage.ToolCall("second", "function", "startChildScene",
                                "{\"childSceneName\":\"" + secondScene + "\",\"investigatorNames\":[\"" + secondInvestigator + "\"]}")))
                .build())));
    }

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"requestCheck", "showMaterial", "purchaseEquipment", "suspendInvestigators", "resumeSuspendedInvestigators", "finishSceneExploration", "endSceneExploration", "resumeWaitingInvestigators", "updateQuickNotes", "updateWeaponState", "stashWeapon", "equipWeaponFromStash", "updateCombatStates", "publishExplorationScenes"})
    void recoverableToolExecutionAndRecordingUseOneTransaction(String toolName) {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        GroupToolCallStore store = mock(GroupToolCallStore.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        RecordingGroupToolCallingManager manager = new RecordingGroupToolCallingManager(
                delegate, store, transactionTemplate);
        Prompt prompt = prompt(Map.of(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));
        ChatResponse response = responseWithCalls(toolName);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"purchaseEquipment,false", "purchaseEquipment,true", "showMaterial,false", "showMaterial,true", "suspendInvestigators,false", "suspendInvestigators,true", "resumeSuspendedInvestigators,false", "resumeSuspendedInvestigators,true", "finishSceneExploration,false", "finishSceneExploration,true", "endSceneExploration,false", "endSceneExploration,true", "resumeWaitingInvestigators,false", "resumeWaitingInvestigators,true", "updateQuickNotes,false", "updateQuickNotes,true", "updateWeaponState,false", "updateWeaponState,true", "stashWeapon,false", "stashWeapon,true", "equipWeaponFromStash,false", "equipWeaponFromStash,true", "updateCombatStates,false", "updateCombatStates,true", "publishExplorationScenes,false", "publishExplorationScenes,true"})
    void toolEffectAndRecordShareCommitWithoutAdvancingCheckpoint(String toolName, boolean recordFails) {
        var mapper = mock(com.me.galchat.mapper.GroupChatToolCallMapper.class);
        var checkpoints = mock(GroupTurnCheckpointService.class);
        var store = new GroupToolCallStore(mapper, tools.jackson.databind.json.JsonMapper.builder().build(), checkpoints);
        List<String> events = new java.util.ArrayList<>();
        var tx = new TransactionTemplate(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { events.add("begin"); }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { events.add("commit"); }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { events.add("rollback"); }
        });
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        Prompt prompt = prompt(Map.of(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L));
        ChatResponse response = responseWithCalls(toolName);
        String callId = response.getResult().getOutput().getToolCalls().getFirst().id();
        var result = mock(ToolExecutionResult.class);
        when(result.conversationHistory()).thenReturn(List.of(org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(callId, toolName, "{\"entries\":[],\"undo\":[]}"))).build()));
        when(delegate.executeToolCalls(prompt, response)).thenAnswer(i -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            events.add("effect"); return result;
        });
        when(mapper.insert(any(com.me.galchat.domain.po.GroupChatToolCall.class))).thenAnswer(i -> {
            events.add("record");
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (recordFails) throw new IllegalStateException("record failed");
            i.<com.me.galchat.domain.po.GroupChatToolCall>getArgument(0).setId(9L); return 1;
        });
        var manager = new RecordingGroupToolCallingManager(delegate, store, tx);
        if (recordFails) assertThatThrownBy(() -> manager.executeToolCalls(prompt, response)).hasMessage("record failed");
        else assertThat(manager.executeToolCalls(prompt, response)).isSameAs(result);
        assertThat(events).containsExactly("begin", "effect", "record", recordFails ? "rollback" : "commit");
        verifyNoInteractions(checkpoints);
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
                mock(GroupTurnCheckpointService.class)) {
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
