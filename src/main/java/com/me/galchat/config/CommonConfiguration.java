package com.me.galchat.config;

import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.memory.TopicAwareMessageChatMemoryAdvisor;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.memory.UserChatMemory;
import com.me.galchat.model.DeepSeekChatModel;
import com.me.galchat.tool.UserCharacterFavorTools;
import com.me.galchat.tool.VectorTools;
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
                                          VectorTools vectorTools,
                                          UserCharacterFavorTools userCharacterFavorTools) {
        return ChatClient
                .builder(model)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(TopicAwareMessageChatMemoryAdvisor.builder(thinkChatMemory, topicBoundaryService).build())
                .defaultTools(vectorTools, userCharacterFavorTools)
                .build();
    }

    @Bean
    public ChatClient normalChatClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model,
                                       TopicBoundaryService topicBoundaryService,
                                       @Qualifier("defaultChatMemory") UserChatMemory defaultChatMemory,
                                       VectorTools vectorTools,
                                       UserCharacterFavorTools userCharacterFavorTools) {
        return ChatClient
                .builder(model)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(TopicAwareMessageChatMemoryAdvisor.builder(defaultChatMemory, topicBoundaryService).build())
                .defaultTools(vectorTools, userCharacterFavorTools)
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
                        你需要判断当前用户消息中是否出现值得未来主动关心的新个人事件、计划、状态或情绪点。
                        仅记录能够在未来主动关心的事件，例如考试、面试、生病、旅行、等待某个结果、期待某个作品、重要纪念日等。
                        忽略普通寒暄、即时动作、泛泛情绪、天气闲聊，以及历史中已经存在且当前没有变化的事件。
                        如果存在新事件，输出严格 JSON：{"time":"推断出的关心时间","eventDescription":"事件描述"}。
                        time 必须是 ISO-8601 本地时间格式，例如 2026-05-20T14:30:00；如果用户没有给出明确时间，则根据当前消息时间和事件类型推断一个适合主动关心的时间。
                        eventDescription 尽量保留用户原话细节。
                        如果不存在新事件，输出空字符串。
                        不要输出 JSON 以外的解释、Markdown 或其他内容。
                        """)
                .build();
    }

    @Bean
    public ChatClient userEventCareClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的主动关怀消息生成机器人。
                        你会收到同一用户和同一角色之间的一组用户事件，每条事件包含时间和描述。
                        请依据这些事件生成一条自然、简短、温柔的关怀消息，像角色主动发来的聊天内容。
                        消息需要把多个事件自然融合，不要逐条罗列，不要提到“事件记录”“数据库”“任务”等系统概念。
                        只输出最终要发送给用户的一条消息，不要输出解释、Markdown 或其他内容。
                        """)
                .build();
    }

    @Bean
    public ChatClient worldStoryOpeningClient(@Qualifier("deepSeekNonThinkingChatModel") DeepSeekChatModel model) {
        return ChatClient
                .builder(model)
                .defaultSystem("""
                        你是一个专业的互动故事开场生成机器人。
                        你会收到用户世界、故事主题，以及用户可能已经指定的标题、场景或开场。
                        请补全缺失部分，生成适合作为多人角色故事开端的信息。
                        输出严格 JSON：{"title":"故事标题","currentScene":"当前场景","opening":"故事开场"}。
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
                .firstMessageSuffixPrompt("""
                        【角色沉浸要求】在你的思考过程（<think>标签内）中，请遵守以下规则：
                        1. 请以角色第一人称进行内心独白，用括号包裹内心活动，例如"（心想：……）"或"(内心OS：……)"
                        2. 用第一人称描写角色的内心感受，例如"我心想""我觉得""我暗自"等
                        3. 思考内容应沉浸在角色中，通过内心独白分析剧情和规划回复
                        """)
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
