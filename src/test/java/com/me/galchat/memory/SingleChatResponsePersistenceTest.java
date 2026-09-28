package com.me.galchat.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.tool.RecordingToolCallingManager;
import com.me.galchat.vector.MutiSearchService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleChatResponsePersistenceTest {
    @BeforeAll
    static void tables() {
        for (var entity : List.of(UserChatHistory.class, UserChatThinkingHistory.class, UserChatToolCall.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesOneCompleteReplyWithoutTools(boolean deepSeek) {
        Fixture fixture = new Fixture(deepSeek, false);
        fixture.iteration.set(2);
        fixture.stream().collectList().block();
        assertThat(fixture.replies).extracting(UserChatHistory::getContent).containsExactly("最终结果。");
        assertThat(fixture.thoughts).extracting(UserChatThinkingHistory::getReasoningContent).containsExactly("完成。");
        assertThat(fixture.calls).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesOneCompleteReplyAfterTwoToolCalls(boolean deepSeek) {
        Fixture fixture = new Fixture(deepSeek, false);
        fixture.stream().collectList().block();
        assertThat(fixture.replies).extracting(UserChatHistory::getContent)
                .containsExactly("正在查询。正在查询。再查一次。最终结果。");
        assertThat(fixture.thoughts).extracting(UserChatThinkingHistory::getReasoningContent)
                .containsExactly("先想。先想。再想。完成。");
        assertThat(fixture.calls).extracting(UserChatToolCall::getToolCallId).containsExactly("call-1", "call-2");
        assertThat(fixture.replies.getFirst().getUserMessageId()).isEqualTo(10L);
        assertThat(fixture.replies.getFirst().getStepNo()).isGreaterThan(fixture.calls.getLast().getStepNo());
        verify(fixture.toolMapper, times(3)).update(any(UserChatToolCall.class), any());
        var promptMessages = fixture.memory.toPromptMessages(List.of(fixture.user, fixture.replies.getFirst()));
        assertThat(promptMessages).hasSize(6);
        assertThat(promptMessages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(promptMessages.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(promptMessages.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(promptMessages.get(4)).isInstanceOf(ToolResponseMessage.class);
        assertThat(promptMessages.get(5).getText()).isEqualTo("正在查询。正在查询。再查一次。最终结果。");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureKeepsExecutedToolsWithoutSavingPartialReply(boolean deepSeek) {
        Fixture fixture = new Fixture(deepSeek, true);
        assertThatThrownBy(() -> fixture.stream().collectList().block()).hasMessage("model failed");
        assertThat(fixture.replies).isEmpty();
        assertThat(fixture.thoughts).isEmpty();
        assertThat(fixture.calls).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationKeepsExecutedToolsWithoutSavingPartialReply(boolean deepSeek) {
        Fixture fixture = new Fixture(deepSeek, false);
        fixture.stream().takeUntil(response -> "最终结果。".equals(response.getResult().getOutput().getText()))
                .collectList().block();
        assertThat(fixture.replies).isEmpty();
        assertThat(fixture.thoughts).isEmpty();
        assertThat(fixture.calls).hasSize(2);
    }

    private static class Fixture {
        final List<UserChatHistory> replies = new ArrayList<>();
        final List<UserChatThinkingHistory> thoughts = new ArrayList<>();
        final List<UserChatToolCall> calls = new ArrayList<>();
        final AtomicInteger iteration = new AtomicInteger();
        final UserChatToolCallMapper toolMapper = mock(UserChatToolCallMapper.class);
        final UserChatHistory user = new UserChatHistory().setId(10L).setType("user").setContent("查询");
        final UserChatMemory memory;
        final ChatClient client;

        Fixture(boolean deepSeek, boolean fail) {
            var histories = mock(UserChatHistoryMapper.class);
            var thinking = mock(UserChatThinkingHistoryMapper.class);
            when(histories.insert(any(UserChatHistory.class))).thenAnswer(invocation -> {
                UserChatHistory row = invocation.getArgument(0);
                row.setId(10L);
                if ("assistant".equals(row.getType())) replies.add(row);
                return 1;
            });
            when(histories.selectList(any())).thenReturn(List.of(user));
            when(thinking.insert(any(UserChatThinkingHistory.class))).thenAnswer(invocation -> {
                thoughts.add(invocation.getArgument(0)); return 1;
            });
            when(toolMapper.insert(any(UserChatToolCall.class))).thenAnswer(invocation -> {
                calls.add(invocation.getArgument(0)); return 1;
            });
            when(toolMapper.update(any(UserChatToolCall.class), any())).thenAnswer(invocation -> {
                UserChatToolCall update = invocation.getArgument(0);
                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserChatToolCall> query = invocation.getArgument(1);
                query.getSqlSegment();
                calls.stream().filter(call -> query.getParamNameValuePairs().containsValue(call.getToolCallId()))
                        .forEach(call -> call.setToolResult(update.getToolResult()));
                return 1;
            });
            when(toolMapper.selectOne(any())).thenAnswer(invocation -> calls.isEmpty() ? null : calls.getLast());
            when(toolMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(calls));
            memory = UserChatMemory.builder(histories).thinkingHistoryMapper(thinking)
                    .toolCallMapper(toolMapper).includeToolCalls(true).build();
            var boundaries = mock(TopicBoundaryService.class);
            when(boundaries.getBoundary(any())).thenReturn(new TopicBoundary(List.of(10L), 10L));
            when(boundaries.updateAfterUserMessage(any(), any())).thenReturn(new TopicBoundary(List.of(10L), 10L));
            var model = mock(ChatModel.class);
            when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
            when(model.stream(any(Prompt.class))).thenAnswer(invocation -> switch (iteration.incrementAndGet()) {
                // A visible prefix followed by a filtered tool-call chunk.
                case 1 -> Flux.just(response(deepSeek, "正在", "先", null),
                        response(deepSeek, "查询。", deepSeek ? "想。" : "先想。", "call-1"));
                // Entire model response shares the filtered tool-call chunk.
                case 2 -> Flux.just(response(deepSeek, "正在查询。再查一次。", "先想。再想。", "call-2"));
                default -> Flux.just(response(deepSeek, "最终结果。", "完成。", null))
                        .concatWith(fail ? Flux.error(new IllegalStateException("model failed")) : Flux.empty());
            });
            var delegate = mock(ToolCallingManager.class);
            when(delegate.executeToolCalls(any(), any())).thenAnswer(invocation -> {
                assertThat(replies).isEmpty();
                assertThat(thoughts).isEmpty();
                Prompt prompt = invocation.getArgument(0);
                ChatResponse response = invocation.getArgument(1);
                var output = response.getResult().getOutput();
                var history = new ArrayList<>(prompt.getInstructions());
                history.add(output);
                history.add(ToolResponseMessage.builder().responses(output.getToolCalls().stream()
                        .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(), "result"))
                        .toList()).build());
                return ToolExecutionResult.builder().conversationHistory(history).build();
            });
            var toolAdvisor = ToolCallingAdvisor.builder()
                    .toolCallingManager(new RecordingToolCallingManager(delegate, memory)).build();
            client = ChatClient.builder(model).defaultAdvisors(
                    TopicAwareMessageChatMemoryAdvisor.builder(memory, boundaries, mock(MutiSearchService.class)).build(),
                    toolAdvisor, new SingleChatResponseRecordingAdvisor(toolAdvisor.getOrder() + 1))
                    .build();
        }

        Flux<ChatResponse> stream() {
            return client.prompt().user("查询")
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, "1:2:10"))
                    .toolContext(Map.of(ChatToolContextConstant.USER_WORLD_ID_KEY, 1L,
                            ChatToolContextConstant.CHARACTER_ID_KEY, 2L)).stream().chatResponse();
        }
    }

    private static ChatResponse response(boolean deepSeek, String content, String reasoning, String toolId) {
        List<AssistantMessage.ToolCall> tools = toolId == null ? List.of()
                : List.of(new AssistantMessage.ToolCall(toolId, "function", "lookup", "{}"));
        AssistantMessage message = deepSeek
                ? new DeepSeekAssistantMessage.Builder().content(content).reasoningContent(reasoning).toolCalls(tools).build()
                : AssistantMessage.builder().content(content).properties(Map.of("reasoningContent", reasoning)).toolCalls(tools).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
