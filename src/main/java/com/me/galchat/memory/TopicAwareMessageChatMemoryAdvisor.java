package com.me.galchat.memory;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.service.ChatUserMessageListener;
import com.me.galchat.vector.MutiSearchService;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.*;

public class TopicAwareMessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    private static final String DEFAULT_CONVERSATION_ID = "default";

    private static final TopicWindowPolicy WINDOW_POLICY =
            new TopicWindowPolicy(ChatConstant.CONTEXT_TOPIC_COUNT,
                    ChatConstant.MAX_CONSECUTIVE_WITHDRAW_COUNT);

    private final UserChatMemory chatMemory;
    private final TopicBoundaryService topicBoundaryService;
    private final MutiSearchService mutiSearchService;
    private final String defaultConversationId;
    private final int order;
    private final Scheduler scheduler;

    private TopicAwareMessageChatMemoryAdvisor(Builder builder) {
        Assert.notNull(builder.chatMemory, "chatMemory cannot be null");
        Assert.notNull(builder.topicBoundaryService, "topicBoundaryService cannot be null");
        Assert.notNull(builder.mutiSearchService, "mutiSearchService cannot be null");
        Assert.hasText(builder.defaultConversationId, "defaultConversationId cannot be null or empty");
        Assert.notNull(builder.scheduler, "scheduler cannot be null");
        this.chatMemory = builder.chatMemory;
        this.topicBoundaryService = builder.topicBoundaryService;
        this.mutiSearchService = builder.mutiSearchService;
        this.defaultConversationId = builder.defaultConversationId;
        this.order = builder.order;
        this.scheduler = builder.scheduler;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Object contextConversationId = chatClientRequest.context().get(ChatMemory.CONVERSATION_ID);
        String conversationId = contextConversationId == null
                ? this.defaultConversationId
                : contextConversationId.toString();
        ConversationInfo baseConversation = new ConversationInfo(conversationId);

        Message userMessage = chatClientRequest.prompt().getLastUserOrToolResponseMessage();
        UserChatHistory savedUserMessage = chatMemory.save(baseConversation, userMessage);

        TopicBoundary boundary = MessageType.USER.equals(userMessage.getMessageType())
                ? topicBoundaryService.updateAfterUserMessage(baseConversation, savedUserMessage)
                : topicBoundaryService.getBoundary(baseConversation);
        ConversationInfo windowConversation = new ConversationInfo(baseConversation.getUserWorldId(),
                baseConversation.getCharacterId(), WINDOW_POLICY.contextStart(boundary.startIds()));

        List<UserChatHistory> windowHistories = chatMemory.listHistories(windowConversation);
        notifyUserMessageSaved(chatClientRequest.prompt().getOptions(), savedUserMessage.getId(),
                windowConversation, windowHistories);
        List<Message> memoryMessages = chatMemory.toPromptMessages(windowHistories);
        List<Message> processedMessages = new ArrayList<>(memoryMessages);
        processedMessages.addAll(removeLastUserOrToolResponseMessage(chatClientRequest.prompt().getInstructions()));
        ensureFirstSystemMessage(processedMessages);
        appendReferenceMemoryToLastUserMessage(processedMessages,
                preSearchReferenceMemory(baseConversation, boundary, windowHistories, savedUserMessage, userMessage));
        appendSuffixToFirstNonSystemMessage(processedMessages,
                firstMessageSuffixPrompt(chatClientRequest.prompt().getOptions()));

        System.out.println(processedMessages);
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
                .get(ChatConstant.TOPIC_CONVERSATION_INFO_CONTEXT_KEY);
        if (conversationInfo == null || chatClientResponse.chatResponse() == null) {
            return chatClientResponse;
        }
        Long userMessageId = (Long) chatClientResponse.context().get(ChatConstant.TOPIC_USER_MESSAGE_ID_CONTEXT_KEY);

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

    static void appendSuffixToFirstNonSystemMessage(List<Message> messages, String suffixPrompt) {
        if (messages == null || messages.isEmpty() || !StringUtils.hasText(suffixPrompt)) {
            return;
        }

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (MessageType.SYSTEM.equals(message.getMessageType())) {
                continue;
            }

            String text = message.getText() == null ? "" : message.getText();
            Message copiedMessage = copyWithText(message, text + suffixPrompt);
            if (copiedMessage == null) {
                continue;
            }
            messages.set(i, copiedMessage);
            return;
        }
    }

    static void appendReferenceMemoryToLastUserMessage(List<Message> messages, String referenceMemory) {
        if (messages == null || messages.isEmpty() || !StringUtils.hasText(referenceMemory)) {
            return;
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!MessageType.USER.equals(message.getMessageType())) {
                continue;
            }

            String text = message.getText() == null ? "" : message.getText();
            Message copiedMessage = copyWithText(message, text + "\n\n【可参考的相关记忆】\n"
                    + "以下内容来自自动检索，不一定可靠；如果与当前对话无关或冲突，请忽略。\n"
                    + referenceMemory);
            if (copiedMessage == null) {
                continue;
            }
            messages.set(i, copiedMessage);
            return;
        }
    }

    private String preSearchReferenceMemory(ConversationInfo conversationInfo, TopicBoundary boundary,
                                            List<UserChatHistory> windowHistories, UserChatHistory savedUserMessage,
                                            Message userMessage) {
        if (!MessageType.USER.equals(userMessage.getMessageType())) {
            return "";
        }

        String query = buildPreSearchQuery(windowHistories, boundary, savedUserMessage);
        if (!StringUtils.hasText(query)) {
            return "";
        }
        return mutiSearchService.searchBeforeChat(conversationInfo.getUserWorldId(),
                conversationInfo.getCharacterId(), query);
    }

    static String buildPreSearchQuery(List<UserChatHistory> histories, TopicBoundary boundary,
                                      UserChatHistory currentUserMessage) {
        if (currentUserMessage == null || !StringUtils.hasText(currentUserMessage.getContent())) {
            return "";
        }

        List<UserChatHistory> queryHistories = new ArrayList<>();
        if (boundary != null && !Objects.equals(boundary.currentStartId(), currentUserMessage.getId())) {
            queryHistories.addAll(previousTurnHistories(histories, boundary.currentStartId(),
                    currentUserMessage.getId()));
        }
        queryHistories.add(currentUserMessage);
        return formatPreSearchHistories(queryHistories);
    }

    private static List<UserChatHistory> previousTurnHistories(List<UserChatHistory> histories, Long currentStartId,
                                                               Long currentUserMessageId) {
        if (histories == null || histories.isEmpty()) {
            return List.of();
        }

        UserChatHistory previousUserMessage = null;
        List<UserChatHistory> assistantMessages = new ArrayList<>();
        for (UserChatHistory history : histories) {
            if (history == null || history.getId() == null) {
                continue;
            }
            if (currentStartId != null && history.getId() < currentStartId) {
                continue;
            }
            if (currentUserMessageId != null && history.getId() >= currentUserMessageId) {
                break;
            }

            if (isUserHistory(history)) {
                previousUserMessage = history;
                assistantMessages.clear();
            }
            else if (previousUserMessage != null && isAssistantHistory(history)) {
                assistantMessages.add(history);
            }
        }

        if (previousUserMessage == null) {
            return List.of();
        }
        List<UserChatHistory> previousTurn = new ArrayList<>();
        previousTurn.add(previousUserMessage);
        previousTurn.addAll(assistantMessages);
        return previousTurn;
    }

    private static String formatPreSearchHistories(List<UserChatHistory> histories) {
        StringBuilder builder = new StringBuilder();
        for (UserChatHistory history : histories) {
            if (!StringUtils.hasText(history.getContent())) {
                continue;
            }
            builder.append(formatPreSearchRole(history)).append(": ")
                    .append(history.getContent())
                    .append('\n');
        }
        return builder.toString();
    }

    private static String formatPreSearchRole(UserChatHistory history) {
        if (isAssistantHistory(history)) {
            return "assistant";
        }
        return "user";
    }

    private static boolean isUserHistory(UserChatHistory history) {
        return MessageType.USER.getValue().equals(normalizeHistoryType(history.getType()));
    }

    private static boolean isAssistantHistory(UserChatHistory history) {
        return MessageType.ASSISTANT.getValue().equals(normalizeHistoryType(history.getType()));
    }

    private static String normalizeHistoryType(String type) {
        if (!StringUtils.hasText(type)) {
            return MessageType.USER.getValue();
        }
        return type.toLowerCase(Locale.ROOT);
    }

    private static Message copyWithText(Message message, String text) {
        if (message instanceof AssistantMessage assistantMessage) {
            return AssistantMessage.builder()
                    .content(text)
                    .properties(assistantMessage.getMetadata())
                    .toolCalls(assistantMessage.getToolCalls())
                    .media(assistantMessage.getMedia())
                    .build();
        }
        if (message instanceof UserMessage userMessage) {
            return UserMessage.builder()
                    .text(text)
                    .metadata(userMessage.getMetadata())
                    .media(userMessage.getMedia())
                    .build();
        }
        if (message instanceof ToolResponseMessage) {
            return null;
        }
        return null;
    }

    private String firstMessageSuffixPrompt(ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolCallingOptions)
                || toolCallingOptions.getToolContext() == null) {
            return "";
        }
        Object suffixPrompt = toolCallingOptions.getToolContext()
                .get(ChatToolContextConstant.FIRST_MESSAGE_SUFFIX_PROMPT_KEY);
        return suffixPrompt instanceof String text ? text : "";
    }

    private Map<String, Object> putContext(Map<String, Object> context, ConversationInfo conversationInfo,
                                           Long userMessageId) {
        Map<String, Object> newContext = new HashMap<>(context);
        newContext.put(ChatConstant.TOPIC_CONVERSATION_INFO_CONTEXT_KEY, conversationInfo);
        newContext.put(ChatConstant.TOPIC_USER_MESSAGE_ID_CONTEXT_KEY, userMessageId);
        return newContext;
    }

    private ChatOptions putToolContext(ChatOptions options, Long userMessageId) {
        if (options instanceof ToolCallingChatOptions toolCallingOptions) {
            Map<String, Object> toolContext = new HashMap<>();
            if (toolCallingOptions.getToolContext() != null) {
                toolContext.putAll(toolCallingOptions.getToolContext());
            }
            toolContext.put(ChatToolContextConstant.USER_MESSAGE_ID_KEY, userMessageId);
            return toolCallingOptions.mutate()
                    .toolContext(toolContext)
                    .build();
        }
        return options.mutate().build();
    }

    private void notifyUserMessageSaved(ChatOptions options, Long userMessageId, ConversationInfo conversationInfo,
                                        List<UserChatHistory> histories) {
        if (!(options instanceof ToolCallingChatOptions toolCallingOptions)) {
            return;
        }
        Map<String, Object> toolContext = toolCallingOptions.getToolContext();
        if (toolContext == null) {
            return;
        }

        Object listener = toolContext.get(ChatToolContextConstant.USER_MESSAGE_LISTENER_KEY);
        if (listener instanceof ChatUserMessageListener userMessageListener) {
            userMessageListener.onUserMessageSaved(userMessageId, conversationInfo, histories);
        }
    }

    public static Builder builder(UserChatMemory chatMemory, TopicBoundaryService topicBoundaryService,
                                  MutiSearchService mutiSearchService) {
        return new Builder(chatMemory, topicBoundaryService, mutiSearchService);
    }

    public static class Builder {

        private final UserChatMemory chatMemory;
        private final TopicBoundaryService topicBoundaryService;
        private final MutiSearchService mutiSearchService;
        private String defaultConversationId = DEFAULT_CONVERSATION_ID;
        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
        private Scheduler scheduler = BaseChatMemoryAdvisor.DEFAULT_SCHEDULER;

        private Builder(UserChatMemory chatMemory, TopicBoundaryService topicBoundaryService,
                        MutiSearchService mutiSearchService) {
            this.chatMemory = chatMemory;
            this.topicBoundaryService = topicBoundaryService;
            this.mutiSearchService = mutiSearchService;
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
