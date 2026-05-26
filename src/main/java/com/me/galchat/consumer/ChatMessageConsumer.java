package com.me.galchat.consumer;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.dto.ChatReplyTaskDTO;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.service.IChatService;
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
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChatMessageConsumer {

    private final RedissonClient redissonClient;
    private final WebSocketServer webSocketServer;
    private final IChatService chatService;

    @Resource(name = "chatTaskExecutor")
    private ThreadPoolTaskExecutor chatTaskExecutor;

    private RBlockingQueue<ChatReplyTaskDTO> replyQueue;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        running = true;
        replyQueue = redissonClient.getBlockingQueue(RedisConstant.REPLY_QUEUE_NAME, new JsonJacksonCodec());
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
            UserChatHistory assistantMessage = chatService.generateReply(task);
            if (assistantMessage == null) {
                return;
            }

            webSocketServer.sendMessageToSession(assistantMessage);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String buildReplyLockKey(ChatReplyTaskDTO task) {
        return buildConversationKey(task) + RedisConstant.REPLY_LOCK_SUFFIX;
    }

    private String buildConversationKey(ChatReplyTaskDTO task) {
        return RedisConstant.CHAT_KEY_PREFIX + task.getUserWorldId() + ":" + task.getCharacterId();
    }
}
