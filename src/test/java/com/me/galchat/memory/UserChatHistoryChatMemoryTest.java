package com.me.galchat.memory;

import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.UserChatHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserChatHistoryChatMemoryTest {

    @Test
    void getKeepsFirstMessageUnchangedWhenSuffixPromptIsBlankByDefault() {
        UserChatHistoryMapper mapper = mapperWithHistories(List.of(
                history(1L, MessageType.USER, "你好")
        ));
        UserChatHistoryChatMemory chatMemory = UserChatHistoryChatMemory.builder(mapper).build();

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
        UserChatHistoryChatMemory chatMemory = UserChatHistoryChatMemory.builder(mapper)
                .firstMessageSuffixPrompt("\n请基于以上内容回答")
                .build();

        List<Message> messages = chatMemory.get("1:2:null");

        assertThat(messages).extracting(Message::getText)
                .containsExactly("你好，有什么可以帮你？\n请基于以上内容回答", "第一句", "第二句");
    }

    private static UserChatHistoryMapper mapperWithHistories(List<UserChatHistory> histories) {
        return (UserChatHistoryMapper) Proxy.newProxyInstance(
                UserChatHistoryMapper.class.getClassLoader(),
                new Class<?>[]{UserChatHistoryMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName()) ? new ArrayList<>(histories) : null
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
}
