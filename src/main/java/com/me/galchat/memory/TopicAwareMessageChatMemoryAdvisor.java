package com.me.galchat.memory;

import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TopicAwareMessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    private static final String CONVERSATION_INFO_CONTEXT_KEY = "topic_conversation_info";
    private static final String USER_MESSAGE_ID_CONTEXT_KEY = "topic_user_message_id";

    private final UserChatMemory chatMemory;
    private final TopicBoundaryService topicBoundaryService;
    private final String defaultConversationId;
    private final int order;
    private final Scheduler scheduler;

    private TopicAwareMessageChatMemoryAdvisor(Builder builder) {
        Assert.notNull(builder.chatMemory, "chatMemory cannot be null");
        Assert.notNull(builder.topicBoundaryService, "topicBoundaryService cannot be null");
        Assert.hasText(builder.defaultConversationId, "defaultConversationId cannot be null or empty");
        Assert.notNull(builder.scheduler, "scheduler cannot be null");
        this.chatMemory = builder.chatMemory;
        this.topicBoundaryService = builder.topicBoundaryService;
        this.defaultConversationId = builder.defaultConversationId;
        this.order = builder.order;
        this.scheduler = builder.scheduler;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String conversationId = getConversationId(chatClientRequest.context(), this.defaultConversationId);
        ConversationInfo baseConversation = new ConversationInfo(conversationId);

        Message userMessage = chatClientRequest.prompt().getLastUserOrToolResponseMessage();
        UserChatHistory savedUserMessage = chatMemory.save(baseConversation, userMessage);

        TopicBoundary boundary = MessageType.USER.equals(userMessage.getMessageType())
                ? topicBoundaryService.updateAfterUserMessage(baseConversation, savedUserMessage)
                : topicBoundaryService.getBoundary(baseConversation);
        ConversationInfo windowConversation = new ConversationInfo(baseConversation.getUserWorldId(),
                baseConversation.getCharacterId(), boundary.windowStartId());

        List<Message> memoryMessages = chatMemory.get(windowConversation);
        List<Message> processedMessages = new ArrayList<>(memoryMessages);
        processedMessages.addAll(removeLastUserOrToolResponseMessage(chatClientRequest.prompt().getInstructions()));
        ensureFirstSystemMessage(processedMessages);

        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt()
                        .mutate()
                        .messages(processedMessages)
                        .chatOptions(putToolContext(chatClientRequest.prompt().getOptions(), savedUserMessage.getId()))
                        .build())
                .context(putContext(chatClientRequest.context(), windowConversation, savedUserMessage.getId()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ConversationInfo conversationInfo = (ConversationInfo) chatClientResponse.context()
                .get(CONVERSATION_INFO_CONTEXT_KEY);
        if (conversationInfo == null || chatClientResponse.chatResponse() == null) {
            return chatClientResponse;
        }
        Long userMessageId = (Long) chatClientResponse.context().get(USER_MESSAGE_ID_CONTEXT_KEY);

        List<Message> assistantMessages = chatClientResponse.chatResponse()
                .getResults()
                .stream()
                .map(Generation::getOutput)
                .map(message -> (Message) message)
                .toList();
        chatMemory.saveAssistantMessages(conversationInfo, userMessageId, assistantMessages);
        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        return Mono.just(chatClientRequest)
                .publishOn(getScheduler())
                .map(request -> before(request, streamAdvisorChain))
                .flatMapMany(streamAdvisorChain::nextStream)
                .transform(flux -> new ChatClientMessageAggregator()
                        .aggregateChatClientResponse(flux, response -> after(response, streamAdvisorChain)));
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Scheduler getScheduler() {
        return scheduler;
    }

    private void ensureFirstSystemMessage(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                Message systemMessage = messages.remove(i);
                messages.addFirst(systemMessage);
                return;
            }
        }
    }

    private List<Message> removeLastUserOrToolResponseMessage(List<Message> messages) {
        List<Message> newMessages = new ArrayList<>(messages);
        for (int i = newMessages.size() - 1; i >= 0; i--) {
            Message message = newMessages.get(i);
            if (MessageType.USER.equals(message.getMessageType()) || MessageType.TOOL.equals(message.getMessageType())) {
                newMessages.remove(i);
                return newMessages;
            }
        }
        return newMessages;
    }

    private Map<String, Object> putContext(Map<String, Object> context, ConversationInfo conversationInfo, Long userMessageId) {
        Map<String, Object> newContext = new HashMap<>(context);
        newContext.put(CONVERSATION_INFO_CONTEXT_KEY, conversationInfo);
        newContext.put(USER_MESSAGE_ID_CONTEXT_KEY, userMessageId);
        return newContext;
    }

    private ChatOptions putToolContext(ChatOptions options, Long userMessageId) {
        ChatOptions copiedOptions = options.copy();
        if (copiedOptions instanceof ToolCallingChatOptions copiedToolCallingOptions) {
            Map<String, Object> toolContext = new HashMap<>();
            if (copiedToolCallingOptions.getToolContext() != null) {
                toolContext.putAll(copiedToolCallingOptions.getToolContext());
            }
            toolContext.put("userMessageId", userMessageId);
            copiedToolCallingOptions.setToolContext(toolContext);
        }
        return copiedOptions;
    }

    public static Builder builder(UserChatMemory chatMemory, TopicBoundaryService topicBoundaryService) {
        return new Builder(chatMemory, topicBoundaryService);
    }

    public static class Builder {

        private final UserChatMemory chatMemory;
        private final TopicBoundaryService topicBoundaryService;
        private String defaultConversationId = ChatMemory.DEFAULT_CONVERSATION_ID;
        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
        private Scheduler scheduler = BaseChatMemoryAdvisor.DEFAULT_SCHEDULER;

        private Builder(UserChatMemory chatMemory, TopicBoundaryService topicBoundaryService) {
            this.chatMemory = chatMemory;
            this.topicBoundaryService = topicBoundaryService;
        }

        public Builder defaultConversationId(String defaultConversationId) {
            this.defaultConversationId = defaultConversationId;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public TopicAwareMessageChatMemoryAdvisor build() {
            return new TopicAwareMessageChatMemoryAdvisor(this);
        }
    }
}
