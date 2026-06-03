package com.me.galchat.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserChatMemoryTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserChatHistory.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserChatThinkingHistory.class);
    }

    @Test
    void saveUserMessageDeletesOneLatestWithdrawnPlaceholder() {
        UserChatHistoryMapper historyMapper = mock(UserChatHistoryMapper.class);
        doAnswer(invocation -> {
            UserChatHistory history = invocation.getArgument(0);
            history.setId(42L);
            return 1;
        }).when(historyMapper).insert(any(UserChatHistory.class));
        when(historyMapper.selectOne(any())).thenReturn(new UserChatHistory()
                .setId(21L)
                .setType(ChatConstant.WITHDRAWN_TYPE));

        UserChatMemory memory = UserChatMemory.builder(historyMapper).build();

        memory.save(new ConversationInfo(1L, 2L, null), new UserMessage("新消息"));

        verify(historyMapper).deleteById(21L);
    }

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

    @Test
    void saveAssistantMessagesTrimsAlreadySavedReasoningPrefix() {
        UserChatHistoryMapper historyMapper = mock(UserChatHistoryMapper.class);
        UserChatThinkingHistoryMapper thinkingMapper = mock(UserChatThinkingHistoryMapper.class);
        UserChatThinkingHistory savedThinking = new UserChatThinkingHistory()
                .setId(10L)
                .setUserMessageId(1L)
                .setStepNo(1)
                .setReasoningContent("先决定调用工具。");
        when(historyMapper.selectList(any())).thenReturn(List.of());
        when(thinkingMapper.selectOne(any())).thenReturn(savedThinking);
        when(thinkingMapper.selectList(any())).thenReturn(List.of(savedThinking));

        UserChatMemory memory = UserChatMemory.builder(historyMapper)
                .thinkingHistoryMapper(thinkingMapper)
                .build();

        memory.saveAssistantMessages(new ConversationInfo(1L, 2L, null), 1L, List.of(
                new DeepSeekAssistantMessage.Builder()
                        .content("工具返回后继续回复。")
                        .reasoningContent("先决定调用工具。工具成功后组织回复。")
                        .build()));

        var thinkingCaptor = forClass(UserChatThinkingHistory.class);
        verify(thinkingMapper).insert(thinkingCaptor.capture());
        assertThat(thinkingCaptor.getValue().getReasoningContent()).isEqualTo("工具成功后组织回复。");
        assertThat(thinkingCaptor.getValue().getStepNo()).isEqualTo(2);
    }

    @Test
    void saveAssistantMessagesTrimsAlreadySavedVisibleAssistantPrefix() {
        UserChatHistoryMapper historyMapper = mock(UserChatHistoryMapper.class);
        List<UserChatHistory> savedAssistantHistories = new ArrayList<>(List.of(new UserChatHistory()
                .setId(10L)
                .setUserMessageId(1L)
                .setStepNo(1)
                .setType(MessageType.ASSISTANT.getValue())
                .setContent("工具调用前说一句。")));
        when(historyMapper.selectList(any()))
                .thenAnswer(invocation -> List.copyOf(savedAssistantHistories));
        doAnswer(invocation -> {
            UserChatHistory history = invocation.getArgument(0);
            history.setId(11L);
            savedAssistantHistories.add(history);
            return 1;
        }).when(historyMapper).insert(any(UserChatHistory.class));

        UserChatMemory memory = UserChatMemory.builder(historyMapper).build();

        memory.saveAssistantMessages(new ConversationInfo(1L, 2L, null), 1L, List.of(
                AssistantMessage.builder()
                        .content("工具调用前说一句。工具返回后继续回复。")
                        .build()));

        var historyCaptor = forClass(UserChatHistory.class);
        verify(historyMapper).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getContent()).isEqualTo("工具返回后继续回复。");
    }
}
