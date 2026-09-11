package com.me.galchat.config;

import com.me.galchat.groupchat.tool.GroupToolCallStore;
import com.me.galchat.groupchat.runtime.GroupChatClientFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonConfigurationTest {

    private final CommonConfiguration configuration = new CommonConfiguration();

    @Test
    void configuresThinkingPerClientWithoutDuplicatingTheModel() {
        assertThinking(
                model -> configuration.chatGroupChatClient(
                        model,
                        groupChatClientFactory()),
                DeepSeekApi.ChatCompletionRequest.Thinking.ENABLED);

        assertThinking(
                configuration::topicClient,
                DeepSeekApi.ChatCompletionRequest.Thinking.DISABLED);
    }

    private void assertThinking(
            Function<DeepSeekChatModel, ChatClient> clientFactory,
            DeepSeekApi.ChatCompletionRequest.Thinking expected) {
        DeepSeekChatModel model = mock(DeepSeekChatModel.class);
        when(model.getOptions()).thenReturn(DeepSeekChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("OK")))));

        clientFactory.apply(model).prompt().user("test").call().content();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOfSatisfying(
                DeepSeekChatOptions.class,
                options -> assertThat(options.getThinking()).isEqualTo(expected));
    }

    private GroupChatClientFactory groupChatClientFactory() {
        return new GroupChatClientFactory(
                ToolCallingManager.builder().build(),
                mock(GroupToolCallStore.class),
                mock(TransactionTemplate.class));
    }
}
