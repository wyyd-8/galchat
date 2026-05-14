package com.me.galchat.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.domain.dto.ChatReplyTaskDTO;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.websocket.WebSocketServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChatMessageConsumer {

    private static final Duration LAST_ASSISTANT_TTL = Duration.ofHours(1);

    private final RedissonClient redissonClient;
    private final ChatClient chatClient;
    private final WebSocketServer webSocketServer;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final StringRedisTemplate redisTemplate;

    @Resource(name = "chatTaskExecutor")
    private ThreadPoolTaskExecutor chatTaskExecutor;

    private RBlockingQueue<ChatReplyTaskDTO> replyQueue;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        running = true;
        replyQueue = redissonClient.getBlockingQueue(ChatQueueNames.REPLY_QUEUE_NAME, new JsonJacksonCodec());
        for (int i = 0; i < 2; i++) {
            chatTaskExecutor.execute(this::consumeReplyTasks);
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        stop();
    }

    private void stop() {
        running = false;
        if (chatTaskExecutor != null) {
            chatTaskExecutor.shutdown();
        }
    }

    private void consumeReplyTasks() {
        while (running) {
            try {
                ChatReplyTaskDTO task = replyQueue.poll(1, TimeUnit.SECONDS);
                if (task == null) {
                    continue;
                }
                handleReplyTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running) {
                    log.error("处理聊天回复任务异常", e);
                }
            }
        }
    }

    private void handleReplyTask(ChatReplyTaskDTO task) {
        RLock lock = redissonClient.getLock(buildReplyLockKey(task));
        lock.lock();
        try {
            UserChatHistory assistantMessage = generateReply(task);
            if (assistantMessage == null) {
                return;
            }

            updateLastChatInfo(assistantMessage);
            redisTemplate.opsForValue().set(buildLastAssistantKey(task),
                    assistantMessage.getContent(), LAST_ASSISTANT_TTL);
            webSocketServer.sendMessageToSession(assistantMessage);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private UserChatHistory generateReply(ChatReplyTaskDTO task) {
        if (task.getUserWorldId() == null || task.getCharacterId() == null || task.getMessage() == null) {
            log.warn("聊天回复任务缺少必要字段: {}", task);
            return null;
        }

        ConversationInfo conversationInfo = new ConversationInfo(task.getUserWorldId(), task.getCharacterId(), null);
        String content = chatClient.prompt()
                .user(task.getMessage())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationInfo.toString()))
                .call()
                .content();
        if (content == null || content.isBlank()) {
            log.warn("ChatClient返回空回复, task:{}", task);
            return null;
        }

        UserChatHistory savedAssistantMessage = latestAssistantMessage(task);
        if (savedAssistantMessage != null) {
            return savedAssistantMessage;
        }

        UserChatHistory fallbackAssistantMessage = new UserChatHistory()
                .setUserWorldId(task.getUserWorldId())
                .setCharacterId(task.getCharacterId())
                .setContent(content)
                .setType(MessageType.ASSISTANT.getValue())
                .setTimestamp(LocalDateTime.now());
        userChatHistoryMapper.insert(fallbackAssistantMessage);
        return fallbackAssistantMessage;
    }

    private void updateLastChatInfo(UserChatHistory assistantMessage) {
        userCharacterInfoMapper.update(new LambdaUpdateWrapper<UserCharacterInfo>()
                .eq(UserCharacterInfo::getUserWorldId, assistantMessage.getUserWorldId())
                .eq(UserCharacterInfo::getCharacterId, assistantMessage.getCharacterId())
                .set(UserCharacterInfo::getLastChatTime, assistantMessage.getTimestamp())
                .set(UserCharacterInfo::getLastChatContent, assistantMessage.getContent()));
    }

    private UserChatHistory latestAssistantMessage(ChatReplyTaskDTO task) {
        return userChatHistoryMapper.selectOne(new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, task.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, task.getCharacterId())
                .eq(UserChatHistory::getType, MessageType.ASSISTANT.getValue())
                .orderByDesc(UserChatHistory::getId)
                .last("limit 1"));
    }

    private String buildReplyLockKey(ChatReplyTaskDTO task) {
        return buildConversationKey(task) + ":reply_lock";
    }

    private String buildLastAssistantKey(ChatReplyTaskDTO task) {
        return buildConversationKey(task) + ":last_assistant";
    }

    private String buildConversationKey(ChatReplyTaskDTO task) {
        return "chat:" + task.getUserWorldId() + ":" + task.getCharacterId();
    }
}
