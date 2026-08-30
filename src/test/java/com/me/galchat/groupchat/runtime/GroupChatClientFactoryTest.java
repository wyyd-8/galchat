package com.me.galchat.groupchat.runtime;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.groupchat.tool.GroupToolCallStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroupChatClientFactoryTest {

    @Test
    void createdClientPreservesPreconfiguredBuilderOptions() {
        CapturingOptionsModel model = new CapturingOptionsModel();
        GroupChatClientFactory factory = new GroupChatClientFactory(
                new ScriptedToolCallingManager(),
                new CapturingToolCallStore(),
                new TransactionTemplate());

        factory.create(ChatClient.builder(model)
                        .defaultOptions(OpenAiChatOptions.builder()
                                .temperature(0.25)))
                .prompt()
                .user("start")
                .call()
                .content();

        assertThat(model.receivedPrompt.getOptions())
                .isInstanceOfSatisfying(
                        OpenAiChatOptions.class,
                        options -> assertThat(options.getTemperature())
                                .isEqualTo(0.25));
    }

    @Test
    void createdClientExecutesAndRecordsTheSpringAiToolLoop() {
        ScriptedToolModel model = new ScriptedToolModel();
        CapturingToolCallStore store = new CapturingToolCallStore();
        GroupChatClientFactory factory = new GroupChatClientFactory(
                new ScriptedToolCallingManager(), store,
                new TransactionTemplate());

        String content = factory.create(ChatClient.builder(model))
                .prompt()
                .user("start")
                .toolContext(Map.of(
                        ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                        41L))
                .call()
                .content();

        assertThat(content).isEqualTo("tool complete");
        assertThat(model.calls).isEqualTo(2);
        assertThat(store.recordedReplyStepId).isEqualTo(41L);
    }

    private static final class CapturingOptionsModel
            implements ChatModel {
        private Prompt receivedPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedPrompt = prompt;
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("done"))));
        }

        @Override
        public OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("model-a").build();
        }
    }

    private static final class ScriptedToolModel implements ChatModel {
        private int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            if (calls == 1) {
                AssistantMessage message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "lookup", "{}")))
                        .build();
                return new ChatResponse(List.of(new Generation(
                        message,
                        ChatGenerationMetadata.builder()
                                .finishReason("TOOL_CALLS")
                                .build())));
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("tool complete"))));
        }

        @Override
        public OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("model-a").build();
        }
    }

    private static final class ScriptedToolCallingManager
            implements ToolCallingManager {

        @Override
        public List<ToolDefinition> resolveToolDefinitions(
                ToolCallingChatOptions options) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(
                Prompt prompt, ChatResponse response) {
            List<Message> history = new ArrayList<>(
                    prompt.getInstructions());
            history.add(response.getResult().getOutput());
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(
                            new ToolResponseMessage.ToolResponse(
                                    "call-1", "lookup", "42")))
                    .build());
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .build();
        }
    }

    private static final class CapturingToolCallStore
            extends GroupToolCallStore {
        private Long recordedReplyStepId;

        private CapturingToolCallStore() {
            super(null, null, null);
        }

        @Override
        public void saveExecution(
                Long replyStepId,
                ChatResponse response,
                ToolExecutionResult result) {
            recordedReplyStepId = replyStepId;
        }

        @Override
        public boolean hasExecution(
                Long replyStepId, String toolName) {
            return false;
        }
    }
}
