package com.me.galchat.memory;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserChatMemoryTest {

    @Test
    void getKeepsFirstMessageUnchangedWhenSuffixPromptIsBlankByDefault() {
        UserChatHistoryMapper mapper = mapperWithHistories(List.of(
                history(1L, MessageType.USER, "你好")
        ));
        UserChatMemory chatMemory = UserChatMemory.builder(mapper).build();

        List<Message> messages = chatMemory.get("1:2:null");

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getText()).isEqualTo("你好");
    }

    @Test
    void getAppendsSuffixPromptToFirstMessageOnly() {
        UserChatHistoryMapper mapper = mapperWithHistories(List.of(
                history(3L, MessageType.USER, "第二句"),
                history(2L, MessageType.USER, "第一句"),
                history(1L, MessageType.ASSISTANT, "你好，有什么可以帮你？")
        ));
        UserChatMemory chatMemory = UserChatMemory.builder(mapper)
                .firstMessageSuffixPrompt("\n请基于以上内容回答")
                .build();

        List<Message> messages = chatMemory.get("1:2:null");

        assertThat(messages).extracting(Message::getText)
                .containsExactly("你好，有什么可以帮你？\n请基于以上内容回答", "第一句", "第二句");
    }

    @Test
    void getRestoresAssistantContentReasoningToolCallsAndToolResponseInSameStep() {
        UserChatHistoryMapper historyMapper = mapperWithHistories(List.of(
                history(2L, MessageType.ASSISTANT, "我查到了").setUserMessageId(1L).setStepNo(1),
                history(1L, MessageType.USER, "查一下天气")
        ));
        UserChatThinkingHistoryMapper thinkingMapper = thinkingMapperWithHistories(List.of(
                new UserChatThinkingHistory()
                        .setUserMessageId(1L)
                        .setStepNo(1)
                        .setReasoningContent("需要调用天气工具")
        ));
        UserChatToolCallMapper toolCallMapper = toolCallMapperWithHistories(List.of(
                new UserChatToolCall()
                        .setUserMessageId(1L)
                        .setStepNo(1)
                        .setToolCallId("call_1")
                        .setToolName("weather")
                        .setToolArguments("{\"city\":\"上海\"}")
                        .setToolResult("晴")
        ));
        UserChatMemory chatMemory = UserChatMemory.builder(historyMapper)
                .thinkingHistoryMapper(thinkingMapper)
                .toolCallMapper(toolCallMapper)
                .includeToolCalls(true)
                .build();

        List<Message> messages = chatMemory.get("1:2:null");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getText()).isEqualTo("查一下天气");

        assertThat(messages.get(1)).isInstanceOf(DeepSeekAssistantMessage.class);
        DeepSeekAssistantMessage assistantMessage = (DeepSeekAssistantMessage) messages.get(1);
        assertThat(assistantMessage.getText()).isEqualTo("我查到了");
        assertThat(assistantMessage.getReasoningContent()).isEqualTo("需要调用天气工具");
        assertThat(assistantMessage.getToolCalls())
                .containsExactly(new AssistantMessage.ToolCall("call_1", "function",
                        "weather", "{\"city\":\"上海\"}"));

        assertThat(messages.get(2)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) messages.get(2);
        assertThat(toolResponseMessage.getResponses())
                .containsExactly(new ToolResponseMessage.ToolResponse("call_1", "weather", "晴"));
    }

    @Test
    void saveAssistantMessagesPersistsContentReasoningAndToolCallsWithSameStep() {
        List<UserChatHistory> savedHistories = new ArrayList<>();
        List<UserChatThinkingHistory> savedThinking = new ArrayList<>();
        List<UserChatToolCall> savedToolCalls = new ArrayList<>();
        UserChatHistoryMapper historyMapper = capturingHistoryMapper(savedHistories);
        UserChatThinkingHistoryMapper thinkingMapper = capturingThinkingMapper(savedThinking);
        UserChatToolCallMapper toolCallMapper = capturingToolCallMapper(savedToolCalls);
        UserChatMemory chatMemory = UserChatMemory.builder(historyMapper)
                .thinkingHistoryMapper(thinkingMapper)
                .toolCallMapper(toolCallMapper)
                .includeToolCalls(true)
                .build();
        DeepSeekAssistantMessage assistantMessage = new DeepSeekAssistantMessage.Builder()
                .content("我查到了")
                .reasoningContent("需要调用天气工具")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function",
                        "weather", "{\"city\":\"上海\"}")))
                .build();

        chatMemory.saveAssistantMessages(new ConversationInfo(1L, 2L, null), 99L, List.of(assistantMessage));

        assertThat(savedHistories).singleElement()
                .satisfies(history -> {
                    assertThat(history.getContent()).isEqualTo("我查到了");
                    assertThat(history.getUserMessageId()).isEqualTo(99L);
                    assertThat(history.getStepNo()).isEqualTo(1);
                    assertThat(history.getType()).isEqualTo(MessageType.ASSISTANT.getValue());
                });
        assertThat(savedThinking).singleElement()
                .satisfies(thinking -> {
                    assertThat(thinking.getReasoningContent()).isEqualTo("需要调用天气工具");
                    assertThat(thinking.getUserMessageId()).isEqualTo(99L);
                    assertThat(thinking.getStepNo()).isEqualTo(1);
                });
        assertThat(savedToolCalls).singleElement()
                .satisfies(toolCall -> {
                    assertThat(toolCall.getToolCallId()).isEqualTo("call_1");
                    assertThat(toolCall.getToolName()).isEqualTo("weather");
                    assertThat(toolCall.getToolArguments()).isEqualTo("{\"city\":\"上海\"}");
                    assertThat(toolCall.getUserMessageId()).isEqualTo(99L);
                    assertThat(toolCall.getStepNo()).isEqualTo(1);
                });
    }

    @Test
    void saveAssistantMessagesUsesRedisStepCounterWhenAvailable() {
        List<UserChatHistory> savedHistories = new ArrayList<>();
        List<UserChatThinkingHistory> savedThinking = new ArrayList<>();
        List<String> incrementedKeys = new ArrayList<>();
        List<String> expiredKeys = new ArrayList<>();
        UserChatHistoryMapper historyMapper = capturingHistoryMapper(savedHistories);
        UserChatThinkingHistoryMapper thinkingMapper = throwingSelectOneThinkingMapper(savedThinking);
        StringRedisTemplate redisTemplate = redisTemplateWithStepCounter(incrementedKeys, expiredKeys, 2L);

        UserChatMemory chatMemory = UserChatMemory.builder(historyMapper)
                .thinkingHistoryMapper(thinkingMapper)
                .redisTemplate(redisTemplate)
                .includeToolCalls(true)
                .build();
        DeepSeekAssistantMessage assistantMessage = new DeepSeekAssistantMessage.Builder()
                .content("第二步")
                .reasoningContent("继续整理工具结果")
                .build();

        chatMemory.saveAssistantMessages(new ConversationInfo(1L, 2L, null), 99L, List.of(assistantMessage));

        assertThat(savedHistories).singleElement()
                .satisfies(history -> assertThat(history.getStepNo()).isEqualTo(2));
        assertThat(savedThinking).singleElement()
                .satisfies(thinking -> assertThat(thinking.getStepNo()).isEqualTo(2));
        assertThat(incrementedKeys).containsExactly("chat:memory:step:99");
        assertThat(expiredKeys).containsExactly("chat:memory:step:99");
    }

    @Test
    void saveAutoSearchInfoPersistsSpecialTypeAndRestoresAsUserMessage() {
        List<UserChatHistory> savedHistories = new ArrayList<>();
        UserChatMemory savingMemory = UserChatMemory.builder(capturingHistoryMapper(savedHistories)).build();

        savingMemory.saveAutoSearchInfo(new ConversationInfo(1L, 2L, null), "自动调用searchInfo结果：杭州天气资料");

        assertThat(savedHistories).singleElement()
                .satisfies(history -> {
                assertThat(history.getType()).isEqualTo(ChatConstant.AUTO_SEARCH_INFO_TYPE);
                    assertThat(history.getContent()).isEqualTo("自动调用searchInfo结果：杭州天气资料");
                });

        UserChatMemory readingMemory = UserChatMemory.builder(mapperWithHistories(List.of(
                history(2L, ChatConstant.AUTO_SEARCH_INFO_TYPE, "自动调用searchInfo结果：杭州天气资料"),
                history(1L, MessageType.USER, "杭州天气")
        ))).build();

        List<Message> messages = readingMemory.get("1:2:null");

        assertThat(messages).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.USER);
        assertThat(messages).extracting(Message::getText)
                .containsExactly("杭州天气", "自动调用searchInfo结果：杭州天气资料");
    }

    private static UserChatHistoryMapper mapperWithHistories(List<UserChatHistory> histories) {
        return (UserChatHistoryMapper) Proxy.newProxyInstance(
                UserChatHistoryMapper.class.getClassLoader(),
                new Class<?>[]{UserChatHistoryMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName()) ? new ArrayList<>(histories) : null
        );
    }

    private static UserChatThinkingHistoryMapper thinkingMapperWithHistories(List<UserChatThinkingHistory> histories) {
        return (UserChatThinkingHistoryMapper) Proxy.newProxyInstance(
                UserChatThinkingHistoryMapper.class.getClassLoader(),
                new Class<?>[]{UserChatThinkingHistoryMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName()) ? new ArrayList<>(histories) : null
        );
    }

    private static UserChatToolCallMapper toolCallMapperWithHistories(List<UserChatToolCall> histories) {
        return (UserChatToolCallMapper) Proxy.newProxyInstance(
                UserChatToolCallMapper.class.getClassLoader(),
                new Class<?>[]{UserChatToolCallMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName()) ? new ArrayList<>(histories) : null
        );
    }

    private static UserChatThinkingHistoryMapper throwingSelectOneThinkingMapper(List<UserChatThinkingHistory> savedThinking) {
        return (UserChatThinkingHistoryMapper) Proxy.newProxyInstance(
                UserChatThinkingHistoryMapper.class.getClassLoader(),
                new Class<?>[]{UserChatThinkingHistoryMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        savedThinking.add((UserChatThinkingHistory) args[0]);
                        return 1;
                    }
                    if ("selectOne".equals(method.getName())) {
                        throw new AssertionError("Redis step counter should avoid latest step DB query");
                    }
                    return null;
                }
        );
    }

    private static StringRedisTemplate redisTemplateWithStepCounter(List<String> incrementedKeys,
                                                                    List<String> expiredKeys,
                                                                    Long nextStepNo) {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = (ValueOperations<String, String>) Proxy.newProxyInstance(
                ValueOperations.class.getClassLoader(),
                new Class<?>[]{ValueOperations.class},
                (proxy, method, args) -> {
                    if ("increment".equals(method.getName()) && args.length == 1) {
                        incrementedKeys.add((String) args[0]);
                        return nextStepNo;
                    }
                    return null;
                }
        );
        return new StringRedisTemplate() {
            @Override
            public ValueOperations<String, String> opsForValue() {
                return valueOperations;
            }

            @Override
            public Boolean hasKey(String key) {
                return true;
            }

            @Override
            public Boolean expire(String key, Duration timeout) {
                expiredKeys.add(key);
                return true;
            }
        };
    }

    private static UserChatHistoryMapper capturingHistoryMapper(List<UserChatHistory> savedHistories) {
        return (UserChatHistoryMapper) Proxy.newProxyInstance(
                UserChatHistoryMapper.class.getClassLoader(),
                new Class<?>[]{UserChatHistoryMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        savedHistories.add((UserChatHistory) args[0]);
                        return 1;
                    }
                    return null;
                }
        );
    }

    private static UserChatThinkingHistoryMapper capturingThinkingMapper(List<UserChatThinkingHistory> savedThinking) {
        return (UserChatThinkingHistoryMapper) Proxy.newProxyInstance(
                UserChatThinkingHistoryMapper.class.getClassLoader(),
                new Class<?>[]{UserChatThinkingHistoryMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        savedThinking.add((UserChatThinkingHistory) args[0]);
                        return 1;
                    }
                    if ("selectOne".equals(method.getName())) {
                        return null;
                    }
                    return null;
                }
        );
    }

    private static UserChatToolCallMapper capturingToolCallMapper(List<UserChatToolCall> savedToolCalls) {
        return (UserChatToolCallMapper) Proxy.newProxyInstance(
                UserChatToolCallMapper.class.getClassLoader(),
                new Class<?>[]{UserChatToolCallMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        savedToolCalls.add((UserChatToolCall) args[0]);
                        return 1;
                    }
                    if ("selectOne".equals(method.getName())) {
                        return null;
                    }
                    if ("selectCount".equals(method.getName())) {
                        return 0L;
                    }
                    return null;
                }
        );
    }

    private static UserChatHistory history(Long id, MessageType messageType, String content) {
        return new UserChatHistory()
                .setId(id)
                .setUserWorldId(1L)
                .setCharacterId(2L)
                .setType(messageType.getValue())
                .setContent(content)
                .setTimestamp(LocalDateTime.now());
    }

    private static UserChatHistory history(Long id, String type, String content) {
        return new UserChatHistory()
                .setId(id)
                .setUserWorldId(1L)
                .setCharacterId(2L)
                .setType(type)
                .setContent(content)
                .setTimestamp(LocalDateTime.now());
    }
}
