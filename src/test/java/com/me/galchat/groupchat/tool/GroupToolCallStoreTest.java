package com.me.galchat.groupchat.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.service.impl.group.GroupTurnCheckpointService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupToolCallStoreTest {

    @Test
    void logsRawDiceToolResponseAndJacksonCauseWhenParsingFails() {
        GroupChatToolCallMapper mapper = mock(GroupChatToolCallMapper.class);
        GroupToolCallStore store = new GroupToolCallStore(
                mapper, JsonMapper.builder().build(),
                mock(GroupTurnCheckpointService.class));
        when(mapper.nextToolStepNo(41L)).thenReturn(1);
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "requestGroupCheck", "{}")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        String malformedResponse = "{not-json";
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "requestGroupCheck", malformedResponse)))
                .build();
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(result.conversationHistory()).thenReturn(List.of(toolResponse));
        Logger logger = (Logger) LoggerFactory.getLogger(GroupToolCallStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> store.saveExecution(41L, response, result))
                    .isInstanceOf(UserRequestException.class)
                    .hasMessage("掷骰工具返回结果无法解析");

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("掷骰工具返回结果解析失败")
                        .contains("responseData={not-json");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getMessage())
                        .contains("Unexpected character");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void savesToolCallAndItsResponseInOneModelStep() {
        GroupChatToolCallMapper mapper = mock(GroupChatToolCallMapper.class);
        GroupToolCallStore store = new GroupToolCallStore(
                mapper, JsonMapper.builder().build(),
                mock(GroupTurnCheckpointService.class));
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
        GroupToolCallStore store = new GroupToolCallStore(
                mapper, JsonMapper.builder().build(),
                mock(GroupTurnCheckpointService.class));

        store.bindDiceSummary(41L, "call-1", 501L);

        verify(mapper).bindDiceSummary(41L, "call-1", 501L);
    }

    @Test
    void locatesFollowUpOnlyWithinRequestedConversation() {
        GroupChatToolCallMapper mapper = mock(GroupChatToolCallMapper.class);
        GroupToolCallStore store = new GroupToolCallStore(
                mapper, JsonMapper.builder().build(),
                mock(GroupTurnCheckpointService.class));
        when(mapper.findLatestDiceSummaryId(7L, Set.of("requestCheck")))
                .thenReturn(501L);

        assertThat(store.requireLatestSummaryId(
                7L, Set.of("requestCheck"))).isEqualTo(501L);

        verify(mapper).findLatestDiceSummaryId(7L, Set.of("requestCheck"));
    }

    @Test
    void directDiceResponseBindsSummaryWhileSavingToolResult() {
        GroupChatToolCallMapper mapper = mock(GroupChatToolCallMapper.class);
        GroupTurnCheckpointService checkpointService =
                mock(GroupTurnCheckpointService.class);
        GroupToolCallStore store = new GroupToolCallStore(
                mapper, JsonMapper.builder().build(), checkpointService);
        when(mapper.nextToolStepNo(41L)).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<GroupChatToolCall>getArgument(0).setId(9L);
            return 1;
        }).when(mapper).insert(any(GroupChatToolCall.class));
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "rollDamage", "{}")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "rollDamage",
                        """
                        {"summary":{"id":501,"conversationId":7,"roundCount":1,"status":"COMPLETED"},
                         "results":[{"id":601,"summaryId":501,"roundNo":1,
                           "resolution":{"type":"DAMAGE","sourceResultId":null,
                             "outcome":{"rawDamage":4},"effect":{"hpAfter":6}}}],
                         "semanticResult":"生命-4"}
                        """)))
                .build();
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(result.conversationHistory()).thenReturn(List.of(toolResponse));

        store.saveExecution(41L, response, result);

        verify(mapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (GroupChatToolCall call) ->
                        Long.valueOf(501L).equals(call.getDiceRollSummaryId())
                                && call.getToolResult().contains("生命-4")));
        verify(checkpointService).recordToolCommitted(41L, 9L);
    }

    @Test
    void mapsDiceSummariesByReplyStepForHistoryReload() {
        GroupChatToolCallMapper mapper = mock(GroupChatToolCallMapper.class);
        GroupToolCallStore store = new GroupToolCallStore(
                mapper, JsonMapper.builder().build(),
                mock(GroupTurnCheckpointService.class));
        when(mapper.selectList(any())).thenReturn(List.of(
                new GroupChatToolCall()
                        .setReplyStepId(41L)
                        .setDiceRollSummaryId(501L),
                new GroupChatToolCall()
                        .setReplyStepId(42L)
                        .setDiceRollSummaryId(502L)));

        Map<Long, Long> ids = store.diceSummaryIdsByReplyStepIds(
                Set.of(41L, 42L));

        assertThat(ids).containsExactlyInAnyOrderEntriesOf(
                Map.of(41L, 501L, 42L, 502L));
    }
}
