package com.me.galchat.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicAwareMessageChatMemoryAdvisorTest {

    @Test
    void appendSuffixToFirstNonSystemMessageKeepsSystemMessageUntouched() {
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("system"),
                new UserMessage("hello"),
                new AssistantMessage("hi")));

        TopicAwareMessageChatMemoryAdvisor.appendSuffixToFirstNonSystemMessage(messages, "\nsuffix");

        assertThat(messages.get(0).getText()).isEqualTo("system");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("hello\nsuffix");
        assertThat(messages.get(2).getText()).isEqualTo("hi");
    }
}
