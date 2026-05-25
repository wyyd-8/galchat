package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.dto.ChatReplyTaskDTO;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.service.ChatToolContext;
import com.me.galchat.service.ChatToolEventListener;
import com.me.galchat.service.IChatService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private static final Duration LAST_ASSISTANT_TTL = Duration.ofHours(1);
    private static final String THINK_TYPE = "think";
    private static final String TOOL_TYPE = "tool";
    private static final String RESPONSE_TYPE = "reponse";

    private final ChatClient deepThinkChatClient;
    private final ChatClient normalChatClient;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public Flux<ChatFluxVO> chat(ChatMessageDTO chatMessageDTO) {
        checkChatRequest(chatMessageDTO);

        ConversationInfo conversationInfo = buildConversationInfo(chatMessageDTO.getUserWorldId(),
                chatMessageDTO.getCharacterId());
        Sinks.Many<ChatFluxVO> toolFlux = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, Object> toolContext = buildToolContext(chatMessageDTO.getUserWorldId(),
                chatMessageDTO.getCharacterId());
        toolContext.put(ChatToolContext.TOOL_EVENT_LISTENER_KEY,
                (ChatToolEventListener) () -> toolFlux.tryEmitNext(new ChatFluxVO(TOOL_TYPE, null)));

        Flux<ChatFluxVO> responseFlux = deepThinkChatClient.prompt()
                .system(buildSystemPrompt(chatMessageDTO.getWorldId(), chatMessageDTO.getUserWorldId(),
                        chatMessageDTO.getCharacterId()))
                .user(chatMessageDTO.getMessage())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationInfo.toString()))
                .toolContext(toolContext)
                .stream()
                .chatResponse()
                .flatMap(this::toChatFlux)
                .doOnComplete(() -> updateLatestChatInfo(chatMessageDTO.getUserWorldId(),
                        chatMessageDTO.getCharacterId()))
                .doOnError(toolFlux::tryEmitError)
                .doFinally(signalType -> toolFlux.tryEmitComplete());

        return Flux.merge(toolFlux.asFlux(), responseFlux);
    }

    @Override
    public UserChatHistory generateReply(ChatReplyTaskDTO task) {
        if (task == null || task.getWorldId() == null || task.getUserWorldId() == null
                || task.getCharacterId() == null || task.getMessage() == null) {
            log.warn("聊天回复任务缺少必要字段: {}", task);
            return null;
        }

        ConversationInfo conversationInfo = buildConversationInfo(task.getUserWorldId(), task.getCharacterId());
        String content = normalChatClient.prompt()
                .system(buildSystemPrompt(task.getWorldId(), task.getUserWorldId(), task.getCharacterId()))
                .user(task.getMessage())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationInfo.toString()))
                .toolContext(buildToolContext(task.getUserWorldId(), task.getCharacterId()))
                .call()
                .content();
        if (!StringUtils.hasText(content)) {
            log.warn("ChatClient返回空回复, task:{}", task);
            return null;
        }

        UserChatHistory assistantMessage = latestAssistantMessage(task.getUserWorldId(), task.getCharacterId());
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
    }

    private Flux<ChatFluxVO> toChatFlux(ChatResponse chatResponse) {
        if (chatResponse == null || CollectionUtils.isEmpty(chatResponse.getResults())) {
            return Flux.empty();
        }

        List<ChatFluxVO> messages = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output instanceof DeepSeekAssistantMessage deepSeekAssistantMessage) {
                addContent(messages, THINK_TYPE, deepSeekAssistantMessage.getReasoningContent());
            }
            if (!CollectionUtils.isEmpty(output.getToolCalls())) {
                messages.add(new ChatFluxVO(TOOL_TYPE, null));
            }
            addContent(messages, RESPONSE_TYPE, output.getText());
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
        toolContext.put(ChatToolContext.USER_WORLD_ID_KEY, userWorldId);
        toolContext.put(ChatToolContext.CHARACTER_ID_KEY, characterId);
        return toolContext;
    }

    private void updateLatestChatInfo(Long userWorldId, Long characterId) {
        UserChatHistory assistantMessage = latestAssistantMessage(userWorldId, characterId);
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

    private UserChatHistory latestAssistantMessage(Long userWorldId, Long characterId) {
        return userChatHistoryMapper.selectOne(new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .eq(UserChatHistory::getType, MessageType.ASSISTANT.getValue())
                .orderByDesc(UserChatHistory::getId)
                .last("limit 1"));
    }

    private void cacheLastAssistant(Long userWorldId, Long characterId, String content) {
        redisTemplate.opsForValue().set(buildLastAssistantKey(userWorldId, characterId), content, LAST_ASSISTANT_TTL);
    }

    private String buildLastAssistantKey(Long userWorldId, Long characterId) {
        return buildConversationKey(userWorldId, characterId) + ":last_assistant";
    }

    private String buildConversationKey(Long userWorldId, Long characterId) {
        return "chat:" + userWorldId + ":" + characterId;
    }
}
