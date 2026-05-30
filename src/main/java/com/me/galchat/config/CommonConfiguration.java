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
                        现在，你需要重写这段对话，使其更简洁且保留有意义的内容，重写后的对话需要保持原有的意思不变，不同部分之间以换行符分隔，格式为 角色:内容
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
                        3. 能根据当前消息或历史时间推断出一个合理的未来关心时间。

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
                .build();
    }

    @Bean
    public ChatClient worldStoryOpeningClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的互动故事开场生成机器人。
                        你会收到用户世界、故事主题，以及用户可能已经指定的标题、场景或开场。
                        如果收到当前场景相关设定候选，应只保留与当前场景直接有关的内容，写入 sceneWorldDetails；如果都无关，sceneWorldDetails 输出空字符串。
                        生成标题、当前场景和开场时，应优先依据 sceneWorldDetails 和故事主题，不要与保留的设定冲突。
                        请补全缺失部分，生成适合作为多人角色故事开端的信息。
                        输出严格 JSON：{"title":"故事标题","currentScene":"当前场景","opening":"故事开场","sceneWorldDetails":"当前场景相关设定"}。
                        opening 应是故事已经发生的起始情况，不要写系统说明，不要给角色添加额外身份、秘密目标或私有动机。
                        不要输出 JSON 以外的解释、Markdown 或其他内容。
                        """)
                .build();
    }

    @Bean
    public ChatClient worldStoryAdvanceClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的互动故事推进整理机器人。
                        你会收到当前故事信息和用户给出的切换语句。
                        请把切换语句整理为一条可插入故事上下文的客观推进消息。
                        如果切换语句表示地点或场景确实改变，currentScene 输出新的当前场景；如果只是时间、天气、状态、物品或其他元素变化，currentScene 保持原场景。
                        输出严格 JSON：{"currentScene":"当前场景","progress":"故事推进消息"}。
                        progress 不要写系统说明，不要给角色添加额外身份、秘密目标或私有动机。
                        不要输出 JSON 以外的解释、Markdown 或其他内容。
                        """)
                .build();
    }

    @Bean
    public ChatClient worldStoryEndClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的互动故事总结机器人。
                        你会收到故事信息、可选的用户离开说明，以及各参与角色在故事窗口中的消息。
                        请生成一段完整、客观、适合长期检索的世界事件概括。
                        输出严格 JSON：{"summary":"完整事件概括"}。
                        summary 应包含故事起因、重要推进、最终状态和参与角色可共同记住的事实；不要写系统说明，不要添加未出现的新角色动机或秘密。
                        不要输出 JSON 以外的解释、Markdown 或其他内容。
                        """)
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
