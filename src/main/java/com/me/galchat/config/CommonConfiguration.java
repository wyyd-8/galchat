package com.me.galchat.config;

import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicAwareMessageChatMemoryAdvisor;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.memory.UserChatHistoryChatMemory;
import com.me.galchat.tool.VectorTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CommonConfiguration {
    @Bean
    public ChatClient chatClient(DeepSeekChatModel model,
                                 TopicAwareMessageChatMemoryAdvisor topicAwareAdvisor,
                                 VectorTools vectorTools) {
        return ChatClient
                .builder(model)
                .defaultSystem("你是一个专业的ai聊天机器人。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(topicAwareAdvisor)
                .defaultTools(vectorTools)
                .build();
    }

    @Bean
    public ChatClient topicClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的对话概要机器人，能够判断当前对话与上一段对话是否连续且为同一话题
                        每个对话均包含对话人，时间戳与对话内容
                        以下是上一段对话与当前对话，最后一个对话为当前对话，其余对话为上一段对话\
                        现在，你需要判断两段对话是否为连续且为同一话题，是输出"true"，不是或无法判断输出"false\"""")
                .build();
    }

    @Bean
    public ChatClient rewriteClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的对话重写机器人，能够重写提供的一段对话
                        你的目标为去除对话中无意义的部分与语气词，尽可能替换 对话中的代词 与 指代不明确的部分，保留有实际意义的内容
                        每个对话均包含对话人，时间戳与对话内容
                        以下是对话内容
                        现在，你需要重写这段对话，使其更简洁且保留有意义的内容，重写后的对话需要保持原有的意思不变，并且不同部分之间以换行符分隔
                        时间戳仅用于重写时参考，输出时不需要保留
                        """)
                .build();
    }

    @Bean
    public UserChatHistoryChatMemory chatMemory(UserChatHistoryMapper userChatHistoryMapper) {
        return UserChatHistoryChatMemory.builder(userChatHistoryMapper)
                .includeToolCalls(true)
                .readOnly(false)
                .maxMessages(50)
                .build();
    }

    @Bean
    public UserChatHistoryChatMemory topicChatMemory(UserChatHistoryMapper userChatHistoryMapper) {
        return UserChatHistoryChatMemory.builder(userChatHistoryMapper)
                .includeToolCalls(false)
                .readOnly(true)
                .maxMessages(50)
                .build();
    }

    @Bean
    public TopicBoundaryService topicBoundaryService(StringRedisTemplate redisTemplate,
                                                     @Qualifier("topicClient") ChatClient topicClient,
                                                     @Qualifier("topicChatMemory") UserChatHistoryChatMemory topicChatMemory) {
        return new TopicBoundaryService(redisTemplate, topicClient, topicChatMemory);
    }

    @Bean
    public TopicAwareMessageChatMemoryAdvisor topicAwareMessageChatMemoryAdvisor(
            @Qualifier("chatMemory") UserChatHistoryChatMemory chatMemory,
            TopicBoundaryService topicBoundaryService) {
        return TopicAwareMessageChatMemoryAdvisor.builder(chatMemory, topicBoundaryService).build();
    }
}
