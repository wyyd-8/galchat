package com.me.galchat.memory;

import com.me.galchat.domain.po.UserChatHistory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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

    @Test
    void appendReferenceMemoryToLastUserMessageMarksReferenceAsUnreliable() {
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("system"),
                new UserMessage("上一轮"),
                new AssistantMessage("回复"),
                new UserMessage("这一轮")));

        TopicAwareMessageChatMemoryAdvisor.appendReferenceMemoryToLastUserMessage(messages,
                "来源: world_detail\n内容: 设定");

        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(3).getText()).contains("这一轮");
        assertThat(messages.get(3).getText()).contains("【可参考的相关记忆】");
        assertThat(messages.get(3).getText()).contains("不一定可靠");
        assertThat(messages.get(3).getText()).contains("如果与当前对话无关或冲突，请忽略");
        assertThat(messages.get(1).getText()).isEqualTo("上一轮");
    }

    @Test
    void buildPreSearchQueryUsesPreviousTurnAndCurrentUserMessage() {
        String query = TopicAwareMessageChatMemoryAdvisor.buildPreSearchQuery(List.of(
                        new UserChatHistory().setId(10L).setType(MessageType.USER.getValue()).setContent("更早用户"),
                        new UserChatHistory().setId(11L).setType(MessageType.ASSISTANT.getValue()).setContent("更早AI"),
                        new UserChatHistory().setId(12L).setType(MessageType.USER.getValue()).setContent("上一轮用户"),
                        new UserChatHistory().setId(13L).setType(MessageType.ASSISTANT.getValue()).setContent("上一轮AI"),
                        new UserChatHistory().setId(14L).setType(MessageType.USER.getValue()).setContent("这一轮用户")),
                new TopicBoundary(List.of(10L), 14L),
                new UserChatHistory().setId(14L).setType(MessageType.USER.getValue()).setContent("这一轮用户"));

        assertThat(query).doesNotContain("更早用户");
        assertThat(query).contains("user: 上一轮用户");
        assertThat(query).contains("assistant: 上一轮AI");
        assertThat(query).contains("user: 这一轮用户");
    }

    @Test
    void buildPreSearchQuerySkipsPreviousTurnWhenCurrentTopicStartsAtCurrentUserMessage() {
        String query = TopicAwareMessageChatMemoryAdvisor.buildPreSearchQuery(List.of(
                        new UserChatHistory().setId(12L).setType(MessageType.USER.getValue()).setContent("上一轮用户"),
                        new UserChatHistory().setId(13L).setType(MessageType.ASSISTANT.getValue()).setContent("上一轮AI"),
                        new UserChatHistory().setId(14L).setType(MessageType.USER.getValue()).setContent("这一轮用户")),
                new TopicBoundary(List.of(12L, 14L), 14L),
                new UserChatHistory().setId(14L).setType(MessageType.USER.getValue()).setContent("这一轮用户"));

        assertThat(query).doesNotContain("上一轮用户");
        assertThat(query).doesNotContain("上一轮AI");
        assertThat(query).isEqualTo("user: 这一轮用户\n");
    }
}
