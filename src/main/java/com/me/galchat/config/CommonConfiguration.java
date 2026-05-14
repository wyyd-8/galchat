package com.me.galchat.config;

import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicAwareMessageChatMemoryAdvisor;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.memory.UserChatHistoryChatMemory;
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
    public ChatClient chatClient(DeepSeekChatModel model, TopicAwareMessageChatMemoryAdvisor topicAwareAdvisor) {
        return ChatClient
                .builder(model)
                .defaultSystem("你是一个专业的ai聊天机器人。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(topicAwareAdvisor)
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
