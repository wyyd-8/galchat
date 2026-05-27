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
import com.me.galchat.service.ChatToolEventListener;
import com.me.galchat.service.ChatUserMessageListener;
import com.me.galchat.service.IChatService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.StoryOperationLockService;
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
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
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

    private final ChatClient deepThinkChatClient;
    private final ChatClient normalChatClient;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;
    private final StringRedisTemplate redisTemplate;
    private final UserEventLogDetector userEventLogDetector;
    private final StoryOperationLockService storyOperationLockService;

    @Resource(name = "userEventLogTaskExecutor")
    private TaskExecutor userEventLogTaskExecutor;

    @Override
    public Flux<ChatFluxVO> chat(ChatMessageDTO chatMessageDTO) {
        checkChatRequest(chatMessageDTO);
        RLock conversationLock = storyOperationLockService.tryLockUserCharacter(chatMessageDTO.getUserWorldId(),
                chatMessageDTO.getCharacterId());
        if (conversationLock == null) {
            throw new UserRequestException("故事切换或结束中，请稍后再对话");
        }

        try {
            ConversationInfo conversationInfo = buildConversationInfo(chatMessageDTO.getUserWorldId(),
                    chatMessageDTO.getCharacterId());
            AtomicReference<Long> userMessageId = new AtomicReference<>();
            Sinks.Many<ChatFluxVO> toolFlux = Sinks.many().unicast().onBackpressureBuffer();
            Map<String, Object> toolContext = buildToolContext(chatMessageDTO.getUserWorldId(),
                    chatMessageDTO.getCharacterId());
            toolContext.put(ChatToolContextConstant.USER_MESSAGE_LISTENER_KEY,
                    buildUserMessageListener(userMessageId, chatMessageDTO.getUserWorldId(),
                            chatMessageDTO.getCharacterId()));
            toolContext.put(ChatToolContextConstant.TOOL_EVENT_LISTENER_KEY,
                    (ChatToolEventListener) () -> toolFlux.tryEmitNext(new ChatFluxVO(ChatConstant.TOOL_TYPE, null)));

            Flux<ChatFluxVO> responseFlux = deepThinkChatClient.prompt()
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
                    .doFinally(signalType -> storyOperationLockService.unlock(conversationLock));
        } catch (RuntimeException e) {
            storyOperationLockService.unlock(conversationLock);
            throw e;
        }
    }

    @Override
    public UserChatHistory generateReply(ChatReplyTaskDTO task) {
        if (task == null || task.getWorldId() == null || task.getUserWorldId() == null
                || task.getCharacterId() == null || task.getMessage() == null) {
            log.warn("聊天回复任务缺少必要字段: {}", task);
            return null;
        }
        RLock conversationLock = storyOperationLockService.tryLockUserCharacter(task.getUserWorldId(),
                task.getCharacterId());
        if (conversationLock == null) {
            log.info("故事切换或结束中，跳过聊天回复任务, userWorldId:{}, characterId:{}",
                    task.getUserWorldId(), task.getCharacterId());
            return null;
        }

        try {
            ConversationInfo conversationInfo = buildConversationInfo(task.getUserWorldId(), task.getCharacterId());
            AtomicReference<Long> userMessageId = new AtomicReference<>();
            Map<String, Object> toolContext = buildToolContext(task.getUserWorldId(), task.getCharacterId());
            toolContext.put(ChatToolContextConstant.USER_MESSAGE_LISTENER_KEY,
                    buildUserMessageListener(userMessageId, task.getUserWorldId(), task.getCharacterId()));
            String content = normalChatClient.prompt()
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
            storyOperationLockService.unlock(conversationLock);
        }
    }

    private Flux<ChatFluxVO> toChatFlux(ChatResponse chatResponse) {
        if (chatResponse == null || CollectionUtils.isEmpty(chatResponse.getResults())) {
            return Flux.empty();
        }

        List<ChatFluxVO> messages = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output instanceof DeepSeekAssistantMessage deepSeekAssistantMessage) {
                addContent(messages, ChatConstant.THINKING_TYPE, deepSeekAssistantMessage.getReasoningContent());
            }
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

    private String buildSystemPrompt(Long worldId, Long userWorldId, Long characterId) {
        StringBuilder prompt = new StringBuilder();
        appendPrompt(prompt, userWorldPrefixService.buildWorldPrompt(worldId));
        appendPrompt(prompt, userCharacterInfoService.buildCharacterPrompt(userWorldId, characterId));
        appendPrompt(prompt, ChatConstant.CHAT_SYSTEM_INSTRUCTIONS);
        return prompt.toString();
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

    private Map<String, Object> buildToolContext(Long userWorldId, Long characterId) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ChatToolContextConstant.USER_WORLD_ID_KEY, userWorldId);
        toolContext.put(ChatToolContextConstant.CHARACTER_ID_KEY, characterId);
        UserWorldPrefix userWorld = userWorldPrefixService.getById(userWorldId);
        if (userWorld != null) {
            toolContext.put(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY, userWorld.getFavorSystemStatus());
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
