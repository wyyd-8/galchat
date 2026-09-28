package com.me.galchat.service.impl.chat;

import com.me.galchat.service.impl.user.UserEventLogDetector;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.utils.CurrentHolder;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.AssistantReasoning;
import com.me.galchat.memory.TopicCompressionTask;
import com.me.galchat.service.ChatToolEventListener;
import com.me.galchat.service.ChatUserMessageListener;
import com.me.galchat.service.IChatService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.trpg.TrpgRunMemoryService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final ChatClient singleChatThinkingClient;
    private final SingleChatRuntimeService singleChatRuntimeService;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;
    private final UserEventLogDetector userEventLogDetector;
    private final SingleChatLockService singleChatLockService;
    private final TrpgRunMemoryService runMemoryService;

    @Resource(name = "topicCompressionTaskExecutor")
    private TaskExecutor topicCompressionTaskExecutor;

    @Resource(name = "userEventLogTaskExecutor")
    private TaskExecutor userEventLogTaskExecutor;

    @Override
    public Flux<ChatFluxVO> chat(ChatMessageDTO chatMessageDTO) {
        checkChatRequest(chatMessageDTO);
        // Authorize on the request thread before deferred model and memory work begins.
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserAuthException("用户未登录");
        }
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(
                userId.longValue(), chatMessageDTO.getUserWorldId(), true);
        if (!Objects.equals(userWorld.getWorldId(), chatMessageDTO.getWorldId())) {
            throw new UserRequestException("世界模板与用户世界不匹配");
        }
        return Flux.defer(() -> {
            SingleChatLockService.OwnedLock conversationLock = singleChatLockService
                    .tryLockWithOwner(chatMessageDTO.getUserWorldId(), chatMessageDTO.getCharacterId());
            if (conversationLock == null) {
                return Flux.error(new UserRequestException("当前单聊正在处理中，请稍后再对话"));
            }

            TopicCompressionTask compression = new TopicCompressionTask(topicCompressionTaskExecutor,
                    () -> singleChatLockService.unlock(conversationLock));
            try {
                ConversationInfo conversationInfo = buildConversationInfo(chatMessageDTO.getUserWorldId(),
                        chatMessageDTO.getCharacterId());
                AtomicReference<Long> userMessageId = new AtomicReference<>();
                Sinks.Many<ChatFluxVO> toolFlux = Sinks.many().unicast().onBackpressureBuffer();
                Map<String, Object> toolContext = buildToolContext(chatMessageDTO.getUserWorldId(),
                        chatMessageDTO.getCharacterId());
                ChatUserMessageListener userMessageListener = buildUserMessageListener(userMessageId,
                        chatMessageDTO.getUserWorldId(), chatMessageDTO.getCharacterId());
                toolContext.put(ChatToolContextConstant.USER_MESSAGE_LISTENER_KEY,
                        (ChatUserMessageListener) (id, info, histories) -> {
                            // The persistent anchor lets a reconnect replace this turn in loaded history.
                            if (StringUtils.hasText(chatMessageDTO.getClientRequestId())) {
                                toolFlux.tryEmitNext(new ChatFluxVO("generation.user", String.valueOf(id)));
                            }
                            userMessageListener.onUserMessageSaved(id, info, histories);
                        });
                toolContext.put(ChatToolContextConstant.TOOL_EVENT_LISTENER_KEY,
                        (ChatToolEventListener) () -> toolFlux.tryEmitNext(new ChatFluxVO(ChatConstant.TOOL_TYPE, null)));

                ChatClient chatClient = singleChatRuntimeService.chatClient(
                        chatMessageDTO.getUserWorldId(), chatMessageDTO.getCharacterId(),
                        singleChatThinkingClient);
                Flux<ChatFluxVO> responseFlux = chatClient.prompt()
                        .system(buildChatSystemPrompt(chatMessageDTO.getWorldId(), chatMessageDTO.getUserWorldId(),
                                chatMessageDTO.getCharacterId()))
                        .user(chatMessageDTO.getMessage())
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationInfo.toString())
                                .param(TopicCompressionTask.CONTEXT_KEY, compression))
                        .toolContext(toolContext)
                        .stream()
                        .chatResponse()
                        .flatMap(this::toChatFlux)
                        .doOnComplete(() -> updateLatestChatInfo(userMessageId.get(), chatMessageDTO.getUserWorldId(),
                                chatMessageDTO.getCharacterId()))
                        .doOnError(toolFlux::tryEmitError)
                        .doFinally(signalType -> toolFlux.tryEmitComplete());

                return compression.attach(Flux.merge(toolFlux.asFlux(), responseFlux));
            } catch (RuntimeException e) {
                compression.finish();
                return Flux.error(e);
            }
        });
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
        if (content != null && !content.isEmpty()) {
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

    /** 普通聊天额外读取当前跑团索引；局内角色仍使用基础提示词。 */
    public String buildChatSystemPrompt(Long worldId, Long userWorldId, Long characterId) {
        StringBuilder prompt = new StringBuilder(buildSystemPrompt(worldId, userWorldId, characterId));
        appendPrompt(prompt, runMemoryService.formatRecentContext(userWorldId, characterId));
        return prompt.toString();
    }

    /** 群聊隐式系统角色共用的世界提示词，不包含任何具体角色身份。 */
    public String buildWorldSystemPrompt(Long worldId, Long userWorldId) {
        StringBuilder prompt = new StringBuilder();
        appendPrompt(prompt, ChatConstant.CHAT_SYSTEM_INSTRUCTIONS_TEMPLATE
                .formatted(buildInteractionRequirements(userWorldId)));
        String worldBackground = userWorldPrefixService.buildWorldPrompt(worldId);
        if (StringUtils.hasText(worldBackground)) {
            appendPrompt(prompt, "【世界背景】\n" + worldBackground);
        }
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

    private Map<String, Object> buildToolContext(Long userWorldId, Long characterId) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ChatToolContextConstant.USER_WORLD_ID_KEY, userWorldId);
        toolContext.put(ChatToolContextConstant.CHARACTER_ID_KEY, characterId);
        UserWorldPrefix userWorld = userWorldPrefixService.getById(userWorldId);
        if (userWorld != null) {
            toolContext.put(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY, userWorld.getFavorSystemStatus());
            if (Boolean.TRUE.equals(userWorld.getAddSpecialPrompt())) {
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
}
