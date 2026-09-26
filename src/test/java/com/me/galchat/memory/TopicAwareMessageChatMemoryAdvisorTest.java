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
    void startsCompressionWithoutWaitingAndUsesOriginalWindow() {
        var memory = org.mockito.Mockito.mock(UserChatMemory.class);
        var boundaries = org.mockito.Mockito.mock(TopicBoundaryService.class);
        var search = org.mockito.Mockito.mock(com.me.galchat.vector.MutiSearchService.class);
        var queued = new java.util.ArrayList<Runnable>();
        var effects = new java.util.ArrayList<String>();
        var task = new TopicCompressionTask(queued::add, () -> effects.add("unlock"));
        var user = new UserChatHistory().setId(40L).setType("user").setContent("换话题");
        var old = new UserChatHistory().setId(10L).setType("assistant").setContent("仍需保留的旧话题");
        org.mockito.Mockito.when(memory.save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(user);
        org.mockito.Mockito.when(boundaries.getBoundary(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TopicBoundary(List.of(10L, 20L, 30L), 32L));
        org.mockito.Mockito.when(boundaries.prepareUpdate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(user)))
                .thenReturn(() -> effects.add("publish"));
        org.mockito.Mockito.when(memory.listHistories(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            com.me.galchat.domain.po.ConversationInfo window = invocation.getArgument(0);
            assertThat(window.getStart()).isEqualTo(10L);
            return List.of(old, user);
        });
        org.mockito.Mockito.when(memory.toPromptMessages(List.of(old, user)))
                .thenReturn(List.of(new AssistantMessage(old.getContent()), new UserMessage(user.getContent())));
        var request = org.springframework.ai.chat.client.ChatClientRequest.builder()
                .prompt(new org.springframework.ai.chat.prompt.Prompt(new UserMessage("换话题"),
                        org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build()))
                .context(java.util.Map.of(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "1:2:10",
                        TopicCompressionTask.CONTEXT_KEY, task)).build();
        var result = TopicAwareMessageChatMemoryAdvisor.builder(memory, boundaries, search).build().before(request, null);
        assertThat(result.prompt().getContents()).contains("仍需保留的旧话题", "换话题");
        assertThat(queued).hasSize(1);
        org.mockito.Mockito.verify(boundaries, org.mockito.Mockito.never())
                .updateAfterUserMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        queued.getFirst().run();
        assertThat(effects).isEmpty();
        task.finish();
        assertThat(effects).containsExactly("publish", "unlock");
    }

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
