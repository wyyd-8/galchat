package com.me.galchat.config;

import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicAwareMessageChatMemoryAdvisor;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.memory.UserChatHistoryChatMemory;
import com.me.galchat.tool.UserEventLogTools;
import com.me.galchat.tool.VectorTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfiguration {
    @Bean
    public ChatClient chatClient(DeepSeekChatModel model,
                                 TopicAwareMessageChatMemoryAdvisor topicAwareAdvisor,
                                 VectorTools vectorTools,
                                 UserEventLogTools userEventLogTools) {
        return ChatClient
                .builder(model)
                .defaultSystem("你是一个专业的ai聊天机器人。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(topicAwareAdvisor)
                .defaultTools(vectorTools, userEventLogTools)
                .build();
    }

    @Bean
    public ChatClient topicClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的对话概要机器人，能够判断当前对话与上一段对话是否连续且为同一话题
                        每个对话均包含对话人，时间戳与对话内容
                        以下是上一段对话与当前对话，最后一个对话为当前对话，其余对话为上一段对话
                        现在，你需要判断两段对话是否为连续且为同一话题，是输出"true"，不是或无法判断输出"false"
                        不要输出其他内容
                        """)
                .build();
    }

    @Bean
    public ChatClient rewriteClient(DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的对话重写机器人，能够重写提供的一段对话
                        你的目标为去除对话中无意义的部分与语气词，尽可能替换 对话中的代词 、 指代不明确的部分 与 时间指代（例如“昨天”，“上周”等） 为 具体人名 与 具体时间（例如2026年3月1日23:30，没有的部分可以省略），保留有实际意义的内容
                        每个对话均包含对话人，时间戳与对话内容
                        现在，你需要重写这段对话，使其更简洁且保留有意义的内容，重写后的对话需要保持原有的意思不变，不同部分之间以换行符分隔，格式为 角色:内容
                        时间戳仅用于重写时参考，输出时不需要保留；不明确的简写不要替换
                        以下是对话内容
                        """)
                .build();
    }

    @Bean
    public UserChatHistoryChatMemory chatMemory(UserChatHistoryMapper userChatHistoryMapper) {
        return UserChatHistoryChatMemory.builder(userChatHistoryMapper)
                .includeToolCalls(true)
                .readOnly(false)
                .build();
    }

    @Bean
    public UserChatHistoryChatMemory topicChatMemory(UserChatHistoryMapper userChatHistoryMapper) {
        return UserChatHistoryChatMemory.builder(userChatHistoryMapper)
                .includeToolCalls(false)
                .readOnly(true)
                .build();
    }

    @Bean
    public TopicAwareMessageChatMemoryAdvisor topicAwareMessageChatMemoryAdvisor(
            @Qualifier("chatMemory") UserChatHistoryChatMemory chatMemory,
            TopicBoundaryService topicBoundaryService) {
        return TopicAwareMessageChatMemoryAdvisor.builder(chatMemory, topicBoundaryService).build();
    }
}
