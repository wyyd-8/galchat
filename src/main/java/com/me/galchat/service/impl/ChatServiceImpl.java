package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.dto.ChatReplyTaskDTO;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.AssistantReasoning;
import com.me.galchat.service.ChatToolEventListener;
import com.me.galchat.service.ChatUserMessageListener;
import com.me.galchat.service.IChatService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final ChatClient singleChatThinkingClient;
    private final ChatClient singleChatNonThinkingClient;
    private final SingleChatRuntimeService singleChatRuntimeService;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;
    private final StringRedisTemplate redisTemplate;
    private final UserEventLogDetector userEventLogDetector;
    private final SingleChatLockService singleChatLockService;

    @Resource(name = "userEventLogTaskExecutor")
    private TaskExecutor userEventLogTaskExecutor;

    @Override
    public Flux<ChatFluxVO> chat(ChatMessageDTO chatMessageDTO) {
        return Flux.defer(() -> {
            checkChatRequest(chatMessageDTO);
            SingleChatLockService.OwnedLock conversationLock = singleChatLockService
                    .tryLockWithOwner(chatMessageDTO.getUserWorldId(), chatMessageDTO.getCharacterId());
            if (conversationLock == null) {
                return Flux.error(new UserRequestException("当前单聊正在处理中，请稍后再对话"));
            }

            try {
                ConversationInfo conversationInfo = buildConversationInfo(chatMessageDTO.getUserWorldId(),
                        chatMessageDTO.getCharacterId());
                AtomicReference<Long> userMessageId = new AtomicReference<>();
                Sinks.Many<ChatFluxVO> toolFlux = Sinks.many().unicast().onBackpressureBuffer();
                Map<String, Object> toolContext = buildToolContext(chatMessageDTO.getUserWorldId(),
                        chatMessageDTO.getCharacterId(), true);
                toolContext.put(ChatToolContextConstant.USER_MESSAGE_LISTENER_KEY,
                        buildUserMessageListener(userMessageId, chatMessageDTO.getUserWorldId(),
                                chatMessageDTO.getCharacterId()));
                toolContext.put(ChatToolContextConstant.TOOL_EVENT_LISTENER_KEY,
                        (ChatToolEventListener) () -> toolFlux.tryEmitNext(new ChatFluxVO(ChatConstant.TOOL_TYPE, null)));

                ChatClient chatClient = singleChatRuntimeService.chatClient(
                        chatMessageDTO.getUserWorldId(), chatMessageDTO.getCharacterId(),
                        singleChatThinkingClient);
                Flux<ChatFluxVO> responseFlux = chatClient.prompt()
                        .system(buildSystemPrompt(chatMessageDTO.getWorldId(), chatMessageDTO.getUserWorldId(),
                                chatMessageDTO.getCharacterId()))
                        .user(chatMessageDTO.getMessage())
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationInfo.toString()))
                        .toolContext(toolContext)
                        .stream()
                        .chatResponse()
                        .flatMap(this::toChatFlux)
                        .doOnComplete(() -> updateLatestChatInfo(userMessageId.get(), chatMessageDTO.getUserWorldId(),
                                chatMessageDTO.getCharacterId()))
                        .doOnError(toolFlux::tryEmitError)
                        .doFinally(signalType -> toolFlux.tryEmitComplete());

                return Flux.merge(toolFlux.asFlux(), responseFlux)
                        .doFinally(signalType -> singleChatLockService.unlock(conversationLock));
            } catch (RuntimeException e) {
                singleChatLockService.unlock(conversationLock);
                return Flux.error(e);
            }
        });
    }

    @Override
    public UserChatHistory generateReply(ChatReplyTaskDTO task) {
        if (task == null || task.getWorldId() == null || task.getUserWorldId() == null
                || task.getCharacterId() == null || task.getMessage() == null) {
            log.warn("聊天回复任务缺少必要字段: {}", task);
            return null;
        }
        RLock conversationLock = singleChatLockService.tryLock(task.getUserWorldId(),
                task.getCharacterId());
        if (conversationLock == null) {
            log.info("当前单聊正在处理中，跳过聊天回复任务, userWorldId:{}, characterId:{}",
                    task.getUserWorldId(), task.getCharacterId());
            return null;
        }

        try {
            ConversationInfo conversationInfo = buildConversationInfo(task.getUserWorldId(), task.getCharacterId());
            AtomicReference<Long> userMessageId = new AtomicReference<>();
            Map<String, Object> toolContext = buildToolContext(task.getUserWorldId(), task.getCharacterId(), false);
            toolContext.put(ChatToolContextConstant.USER_MESSAGE_LISTENER_KEY,
                    buildUserMessageListener(userMessageId, task.getUserWorldId(), task.getCharacterId()));
            ChatClient chatClient = singleChatRuntimeService.chatClient(
                    task.getUserWorldId(), task.getCharacterId(), singleChatNonThinkingClient);
            String content = chatClient.prompt()
                    .system(buildSystemPrompt(task.getWorldId(), task.getUserWorldId(), task.getCharacterId()))
                    .user(task.getMessage())
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationInfo.toString()))
                    .toolContext(toolContext)
                    .call()
                    .content();
            if (!StringUtils.hasText(content)) {
                log.warn("ChatClient返回空回复, task:{}", task);
                return null;
            }

            UserChatHistory assistantMessage = latestAssistantMessage(userMessageId.get(), task.getUserWorldId(),
                    task.getCharacterId());
            if (assistantMessage == null) {
                assistantMessage = new UserChatHistory()
                        .setUserWorldId(task.getUserWorldId())
                        .setCharacterId(task.getCharacterId())
                        .setContent(content)
                        .setType(MessageType.ASSISTANT.getValue())
                        .setTimestamp(LocalDateTime.now());
                userChatHistoryMapper.insert(assistantMessage);
            }

            updateLastChatInfo(assistantMessage);
            cacheLastAssistant(task.getUserWorldId(), task.getCharacterId(), assistantMessage.getContent());
            return assistantMessage;
        } finally {
            singleChatLockService.unlock(conversationLock);
        }
    }

    private Flux<ChatFluxVO> toChatFlux(ChatResponse chatResponse) {
        if (chatResponse == null || CollectionUtils.isEmpty(chatResponse.getResults())) {
            return Flux.empty();
        }

        List<ChatFluxVO> messages = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation.getOutput();
            addContent(messages, ChatConstant.THINKING_TYPE, AssistantReasoning.get(output));
            addContent(messages, ChatConstant.RESPONSE_TYPE, output.getText());
        }
        return Flux.fromIterable(messages);
    }

    private void addContent(List<ChatFluxVO> messages, String type, String content) {
        if (StringUtils.hasText(content)) {
            messages.add(new ChatFluxVO(type, content));
        }
    }

    private void checkChatRequest(ChatMessageDTO chatMessageDTO) {
        if (chatMessageDTO == null) {
            throw new UserRequestException("聊天请求不能为空");
        }
        if (chatMessageDTO.getWorldId() == null) {
            throw new UserRequestException("世界id不能为空");
        }
        if (chatMessageDTO.getUserWorldId() == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        if (chatMessageDTO.getCharacterId() == null) {
            throw new UserRequestException("角色id不能为空");
        }
        if (!StringUtils.hasText(chatMessageDTO.getMessage())) {
            throw new UserRequestException("消息内容不能为空");
        }
    }

    /** 单聊与群聊共用的角色基础提示词，包含世界、角色、好感和长期用户信息。 */
    public String buildSystemPrompt(Long worldId, Long userWorldId, Long characterId) {
        StringBuilder prompt = new StringBuilder(buildWorldSystemPrompt(worldId, userWorldId));
        appendPrompt(prompt, userCharacterInfoService.buildCharacterPrompt(userWorldId, characterId));
        return prompt.toString();
    }

    /** 群聊隐式系统角色共用的世界提示词，不包含任何具体角色身份。 */
    public String buildWorldSystemPrompt(Long worldId, Long userWorldId) {
        StringBuilder prompt = new StringBuilder();
        appendPrompt(prompt, ChatConstant.CHAT_SYSTEM_INSTRUCTIONS_TEMPLATE
                .formatted(buildInteractionRequirements(userWorldId)));
        appendPrompt(prompt, userWorldPrefixService.buildWorldPrompt(worldId));
        return prompt.toString();
    }

    private String buildInteractionRequirements(Long userWorldId) {
        UserWorldPrefix userWorld = userWorldPrefixService.getById(userWorldId);
        if (userWorld != null && Boolean.FALSE.equals(userWorld.getDailyCompanionMode())) {
            return ChatConstant.IMMERSIVE_ROLE_INTERACTION_REQUIREMENTS;
        }
        return ChatConstant.DAILY_COMPANION_INTERACTION_REQUIREMENTS;
    }

    private void appendPrompt(StringBuilder prompt, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        if (!prompt.isEmpty()) {
            prompt.append('\n');
        }
        prompt.append(content);
    }

    private ConversationInfo buildConversationInfo(Long userWorldId, Long characterId) {
        return new ConversationInfo(userWorldId, characterId, null);
    }

    private Map<String, Object> buildToolContext(Long userWorldId, Long characterId, boolean thinkingRequest) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ChatToolContextConstant.USER_WORLD_ID_KEY, userWorldId);
        toolContext.put(ChatToolContextConstant.CHARACTER_ID_KEY, characterId);
        UserWorldPrefix userWorld = userWorldPrefixService.getById(userWorldId);
        if (userWorld != null) {
            toolContext.put(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY, userWorld.getFavorSystemStatus());
            if (thinkingRequest && Boolean.TRUE.equals(userWorld.getThinkStatus())
                    && Boolean.TRUE.equals(userWorld.getAddSpecialPrompt())) {
                toolContext.put(ChatToolContextConstant.FIRST_MESSAGE_SUFFIX_PROMPT_KEY,
                        ChatConstant.SPECIAL_FIRST_MESSAGE_SUFFIX_PROMPT);
            }
        }
        return toolContext;
    }

    private ChatUserMessageListener buildUserMessageListener(AtomicReference<Long> userMessageId,
                                                             Long userWorldId,
                                                             Long characterId) {
        return (savedUserMessageId, conversationInfo, histories) -> {
            userMessageId.set(savedUserMessageId);
            detectUserEventLog(userWorldId, characterId, savedUserMessageId, histories);
        };
    }

    private void detectUserEventLog(Long userWorldId, Long characterId, Long userMessageId,
                                    List<UserChatHistory> histories) {
        List<UserChatHistory> historySnapshot = histories == null ? List.of() : new ArrayList<>(histories);
        userEventLogTaskExecutor.execute(() -> {
            try {
                userEventLogDetector.detectAndSave(userWorldId, characterId, userMessageId, historySnapshot);
            } catch (Exception e) {
                log.warn("用户事件判断失败, userWorldId:{}, characterId:{}, userMessageId:{}",
                        userWorldId, characterId, userMessageId, e);
            }
        });
    }

    private void updateLatestChatInfo(Long userMessageId, Long userWorldId, Long characterId) {
        UserChatHistory assistantMessage = latestAssistantMessage(userMessageId, userWorldId, characterId);
        if (assistantMessage == null) {
            return;
        }
        updateLastChatInfo(assistantMessage);
        cacheLastAssistant(userWorldId, characterId, assistantMessage.getContent());
    }

    private void updateLastChatInfo(UserChatHistory assistantMessage) {
        userCharacterInfoMapper.update(new LambdaUpdateWrapper<UserCharacterInfo>()
                .eq(UserCharacterInfo::getUserWorldId, assistantMessage.getUserWorldId())
                .eq(UserCharacterInfo::getCharacterId, assistantMessage.getCharacterId())
                .set(UserCharacterInfo::getLastChatTime, assistantMessage.getTimestamp())
                .set(UserCharacterInfo::getLastChatContent, assistantMessage.getContent()));
    }

    private UserChatHistory latestAssistantMessage(Long userMessageId, Long userWorldId, Long characterId) {
        if (userMessageId == null) {
            return null;
        }
        return userChatHistoryMapper.selectOne(new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .eq(UserChatHistory::getType, MessageType.ASSISTANT.getValue())
                .eq(UserChatHistory::getUserMessageId, userMessageId)
                .orderByDesc(UserChatHistory::getId)
                .last("limit 1"));
    }

    private void cacheLastAssistant(Long userWorldId, Long characterId, String content) {
        redisTemplate.opsForValue().set(buildLastAssistantKey(userWorldId, characterId), content,
                RedisConstant.LAST_ASSISTANT_TTL);
    }

    private String buildLastAssistantKey(Long userWorldId, Long characterId) {
        return buildConversationKey(userWorldId, characterId) + RedisConstant.LAST_ASSISTANT_SUFFIX;
    }

    private String buildConversationKey(Long userWorldId, Long characterId) {
        return RedisConstant.CHAT_KEY_PREFIX + userWorldId + ":" + characterId;
    }
}
