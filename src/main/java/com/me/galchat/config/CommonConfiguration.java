package com.me.galchat.config;

import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.memory.TopicAwareMessageChatMemoryAdvisor;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.memory.UserChatMemory;
import com.me.galchat.model.DeepSeekChatModel;
import com.me.galchat.tool.UserCharacterFavorTools;
import com.me.galchat.tool.UserCharacterInfoTools;
import com.me.galchat.tool.VectorTools;
import com.me.galchat.vector.MutiSearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CommonConfiguration {
    @Bean
    public ChatClient deepThinkChatClient(@Qualifier("deepSeekThinkingChatModel") DeepSeekChatModel model,
                                          TopicBoundaryService topicBoundaryService,
                                          @Qualifier("thinkChatMemory") UserChatMemory thinkChatMemory,
                                          MutiSearchService mutiSearchService,
                                          VectorTools vectorTools,
                                          UserCharacterFavorTools userCharacterFavorTools,
                                          UserCharacterInfoTools userCharacterInfoTools) {
        return ChatClient
                .builder(model)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(TopicAwareMessageChatMemoryAdvisor.builder(thinkChatMemory, topicBoundaryService,
                        mutiSearchService).build())
                .defaultTools(vectorTools, userCharacterFavorTools, userCharacterInfoTools)
                .build();
    }

    @Bean
    public ChatClient normalChatClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model,
                                       TopicBoundaryService topicBoundaryService,
                                       @Qualifier("defaultChatMemory") UserChatMemory defaultChatMemory,
                                       MutiSearchService mutiSearchService,
                                       VectorTools vectorTools,
                                       UserCharacterFavorTools userCharacterFavorTools,
                                       UserCharacterInfoTools userCharacterInfoTools) {
        return ChatClient
                .builder(model)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(TopicAwareMessageChatMemoryAdvisor.builder(defaultChatMemory, topicBoundaryService,
                        mutiSearchService).build())
                .defaultTools(vectorTools, userCharacterFavorTools, userCharacterInfoTools)
                .build();
    }

    @Bean
    public ChatClient chatGroupChatClient(
            @Qualifier("groupDeepSeekThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient.builder(model)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    public ChatClient trpgGroupChatClient(
            @Qualifier("groupDeepSeekThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient.builder(model)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    public ChatClient groupNonThinkingChatClient(
            @Qualifier("groupDeepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient.builder(model).build();
    }

    @Bean
    public ChatClient topicClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
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
    public ChatClient rewriteClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的对话重写机器人，能够重写提供的一段对话
                        你的目标为去除对话中无意义的部分与语气词，尽可能替换 对话中的代词 、 指代不明确的部分 与 时间指代（例如“昨天”，“上周”等） 为 具体人名 与 具体时间（例如2026年3月1日23:30，没有的部分可以省略），保留有实际意义的内容
                        不需要保留括号中的内容
                        每个对话均包含对话人，时间戳与对话内容
                        现在，你需要重写这段对话，使其更简洁且保留有意义的内容，重写后的对话需要保持原有的意思不变，不同部分之间以换行符分隔，格式为 "assistant":内容 或 "user":内容
                        时间戳仅用于重写时参考，输出时不需要保留；不明确的简写不要替换
                        以下是对话内容
                        """)
                .build();
    }

    @Bean
    public ChatClient userEventLogClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
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
    public ChatClient userEventCareClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
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
    public UserChatMemory thinkChatMemory(UserChatHistoryMapper userChatHistoryMapper,
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
}
