package com.me.galchat.config;

import com.me.galchat.groupchat.tool.GroupToolCallStore;
import com.me.galchat.groupchat.tool.RecordingGroupToolCallingManager;
import com.me.galchat.groupchat.runtime.GroupChatClientFactory;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.memory.UserChatMemory;
import com.me.galchat.memory.TopicCompressionPrompts;
import com.me.galchat.singlechat.SingleChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class CommonConfiguration {
    @Bean
    public ChatClient singleChatThinkingClient(
            DeepSeekChatModel model,
            SingleChatClientFactory clientFactory) {
        return clientFactory.create(ChatClient.builder(model)
                .defaultOptions(DeepSeekChatOptions.builder().enableThinking()));
    }

    @Bean
    public ChatClient singleChatNonThinkingClient(
            DeepSeekChatModel model,
            SingleChatClientFactory clientFactory) {
        return clientFactory.create(ChatClient.builder(model)
                .defaultOptions(DeepSeekChatOptions.builder().disableThinking()));
    }

    @Bean
    public ChatClient chatGroupChatClient(
            DeepSeekChatModel model,
            GroupChatClientFactory clientFactory) {
        return clientFactory.create(ChatClient.builder(model)
                .defaultOptions(
                        DeepSeekChatOptions.builder().enableThinking()));
    }

    @Bean
    public ChatClient trpgGroupChatClient(
            DeepSeekChatModel model,
            GroupChatClientFactory clientFactory) {
        return clientFactory.create(ChatClient.builder(model)
                .defaultOptions(
                        DeepSeekChatOptions.builder().enableThinking()));
    }

    @Bean
    public ChatClient groupNonThinkingChatClient(
            DeepSeekChatModel model,
            ToolCallingManager toolCallingManager,
            GroupToolCallStore groupToolCallStore,
            TransactionTemplate transactionTemplate) {
        return ChatClient.builder(model)
                .defaultOptions(DeepSeekChatOptions.builder().disableThinking())
                .defaultAdvisors(groupToolCallingAdvisor(
                        toolCallingManager, groupToolCallStore, transactionTemplate))
                .build();
    }

    @Bean
    public ChatClient topicClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultOptions(DeepSeekChatOptions.builder().model("deepseek-flash").enableThinking())
                .defaultSystem(TopicCompressionPrompts.SCORE)
                .build();
    }

    @Bean
    public ChatClient rewriteClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultOptions(DeepSeekChatOptions.builder().disableThinking())
                .defaultSystem(TopicCompressionPrompts.SUMMARIZE)
                .build();
    }

    @Bean
    public ChatClient userEventLogClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultOptions(DeepSeekChatOptions.builder().disableThinking())
                .defaultSystem("""
                        你是一个专业的用户事件判断机器人。
                        你会收到当前窗口的对话历史，其中每条对话包含对话人、时间戳与对话内容；标记为“当前用户消息”的 user 对话是本次需要判断的消息。

                        【判断原则】
                        只判断“当前用户消息”，历史只用于理解指代和避免重复记录。
                        默认输出空字符串；只有当前用户消息同时满足以下条件时，才记录事件：
                        1. 用户明确表达了自己的现实事件、计划、承诺、等待结果、健康状态、重要日期或持续情绪原因。
                        2. 该事件适合在未来某个时间主动关心，例如考试、面试、旅行、手术/生病恢复、等待录取/开奖/回复、纪念日、重要工作或学习安排。
                        3. 如果这是一个未发生的事件，你需要根据当前消息或历史时间推断出一个合理的未来关心时间；如果这是一个已发生的事件，你需要输出当前时间。

                        【必须忽略】
                        普通寒暄、语气词、闲聊、玩笑、即时动作、单纯角色扮演动作、天气闲聊、泛泛情绪但没有具体原因或后续节点的表达。
                        例如“嗯”“好”“哈哈”“随便”“我不知道”“有点烦”“好困”“在吃饭”“你真好”“今天还行”等都输出空字符串。
                        历史中已经记录过且当前没有新变化的事件也输出空字符串。

                        如果存在新事件，输出严格 JSON：{"time":"推断出的关心时间","eventDescription":"事件描述"}。
                        time 必须是 ISO-8601 本地时间格式，例如 2026-05-20T14:30:00；无法合理推断未来关心时间时输出空字符串。
                        eventDescription 尽量保留用户原话细节。
                        如果不确定是否值得记录，输出空字符串。
                        不要输出 JSON 以外的解释、Markdown 或其他内容。
                        """)
                .build();
    }

    @Bean
    public ChatClient userEventCareClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultOptions(DeepSeekChatOptions.builder().disableThinking())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    @Primary
    public UserChatMemory defaultChatMemory(UserChatHistoryMapper userChatHistoryMapper,
                                            UserChatThinkingHistoryMapper userChatThinkingHistoryMapper,
                                            UserChatToolCallMapper userChatToolCallMapper,
                                            StringRedisTemplate redisTemplate) {
        return UserChatMemory.builder(userChatHistoryMapper)
                .thinkingHistoryMapper(userChatThinkingHistoryMapper)
                .toolCallMapper(userChatToolCallMapper)
                .redisTemplate(redisTemplate)
                .includeToolCalls(true)
                .readOnly(false)
                .build();
    }

    @Bean
    public UserChatMemory topicChatMemory(UserChatHistoryMapper userChatHistoryMapper) {
        return UserChatMemory.builder(userChatHistoryMapper)
                .includeToolCalls(false)
                .includeAutoSearchInfo(false)
                .readOnly(true)
                .build();
    }

    private ToolCallingAdvisor groupToolCallingAdvisor(
            ToolCallingManager toolCallingManager,
            GroupToolCallStore groupToolCallStore,
            TransactionTemplate transactionTemplate) {
        return toolCallingAdvisor(new RecordingGroupToolCallingManager(
                toolCallingManager, groupToolCallStore, transactionTemplate));
    }

    private ToolCallingAdvisor toolCallingAdvisor(ToolCallingManager toolCallingManager) {
        return ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .build();
    }
}
