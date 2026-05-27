package com.me.galchat.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.constant.UserEventLogConstant;
import com.me.galchat.domain.dto.UserEventLogDelayTaskDTO;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.service.IUserEventLogService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.websocket.WebSocketServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserEventLogConsumer {

    private final RedissonClient redissonClient;
    private final WebSocketServer webSocketServer;
    private final IUserEventLogService userEventLogService;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;

    @Resource(name = "userEventCareClient")
    private ChatClient userEventCareClient;

    @Resource(name = "userEventCareTaskExecutor")
    private ThreadPoolTaskExecutor userEventCareTaskExecutor;

    private RBlockingQueue<UserEventLogDelayTaskDTO> userEventLogQueue;
    private RDelayedQueue<UserEventLogDelayTaskDTO> userEventLogDelayedQueue;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        running = true;
        userEventLogQueue = redissonClient.getBlockingQueue(RedisConstant.USER_EVENT_LOG_DELAY_QUEUE_NAME,
                new JsonJacksonCodec());
        userEventLogDelayedQueue = redissonClient.getDelayedQueue(userEventLogQueue);
        userEventCareTaskExecutor.execute(this::consumeUserEventLogTasks);
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
        if (userEventCareTaskExecutor != null) {
            userEventCareTaskExecutor.shutdown();
        }
    }

    private void consumeUserEventLogTasks() {
        while (running) {
            try {
                UserEventLogDelayTaskDTO task = userEventLogQueue.poll(1, TimeUnit.SECONDS);
                if (task == null) {
                    continue;
                }
                handleUserEventLogTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running) {
                    log.error("处理用户事件关怀任务异常", e);
                }
            }
        }
    }

    private void handleUserEventLogTask(UserEventLogDelayTaskDTO task) {
        if (task == null || task.getUserWorldId() == null || task.getCharacterId() == null
                || task.getUserEventLogIds() == null || task.getUserEventLogIds().isEmpty()) {
            return;
        }
        UserWorldPrefix userWorld = userWorldPrefixService.getById(task.getUserWorldId());
        if (userWorld == null || !Boolean.TRUE.equals(userWorld.getAcitvePushStatus())) {
            log.info("用户世界未开启主动推送，跳过用户事件关怀任务, userWorldId:{}, characterId:{}",
                    task.getUserWorldId(), task.getCharacterId());
            return;
        }

        UserCharacterInfo userCharacterInfo = userCharacterInfoMapper.selectOne(
                new LambdaQueryWrapper<UserCharacterInfo>()
                        .eq(UserCharacterInfo::getUserWorldId, task.getUserWorldId())
                        .eq(UserCharacterInfo::getCharacterId, task.getCharacterId())
                        .last("limit 1"));
        if (userCharacterInfo == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (isRecentlyChatted(userCharacterInfo.getLastChatTime(), now)) {
            retryUserEventLogTask(task);
            return;
        }

        List<UserEventLog> eventLogs = userEventLogService.listByIds(task.getUserEventLogIds())
                .stream()
                .filter(eventLog -> task.getUserWorldId().equals(eventLog.getUserWorldId()))
                .filter(eventLog -> task.getCharacterId().equals(eventLog.getCharacterId()))
                .sorted(Comparator.comparing(UserEventLog::getTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserEventLog::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (eventLogs.isEmpty()) {
            return;
        }

        String content = userEventCareClient.prompt()
                .user(formatUserEventCarePrompt(eventLogs))
                .call()
                .content();
        if (!StringUtils.hasText(content)) {
            return;
        }

        UserChatHistory message = new UserChatHistory()
                .setUserWorldId(task.getUserWorldId())
                .setCharacterId(task.getCharacterId())
                .setType(MessageType.ASSISTANT.getValue())
                .setContent(content.trim())
                .setTimestamp(LocalDateTime.now());
        userChatHistoryMapper.insert(message);
        updateLastChatInfo(message);

        try {
            webSocketServer.sendMessageToSession(message);
        } catch (Exception e) {
            log.warn("推送用户事件关怀消息失败, userWorldId:{}, characterId:{}, messageId:{}",
                    message.getUserWorldId(), message.getCharacterId(), message.getId(), e);
        }
    }

    private boolean isRecentlyChatted(LocalDateTime lastChatTime, LocalDateTime now) {
        return lastChatTime != null && lastChatTime.isAfter(now.minus(UserEventLogConstant.MIN_CHAT_IDLE_TIME));
    }

    private void retryUserEventLogTask(UserEventLogDelayTaskDTO task) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        if (retryCount >= UserEventLogConstant.MAX_RETRY_COUNT) {
            log.info("用户事件关怀任务超过最大重试次数, userWorldId:{}, characterId:{}, eventIds:{}",
                    task.getUserWorldId(), task.getCharacterId(), task.getUserEventLogIds());
            return;
        }

        task.setRetryCount(retryCount + 1);
        userEventLogDelayedQueue.offer(task, UserEventLogConstant.RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String formatUserEventCarePrompt(List<UserEventLog> eventLogs) {
        StringBuilder builder = new StringBuilder("用户事件：\n");
        for (UserEventLog eventLog : eventLogs) {
            builder.append("- 时间：")
                    .append(eventLog.getTime() == null ? "unknown" : eventLog.getTime())
                    .append("；内容：")
                    .append(eventLog.getEventDescription())
                    .append('\n');
        }
        return builder.toString();
    }

    private void updateLastChatInfo(UserChatHistory message) {
        userCharacterInfoMapper.update(new UserCharacterInfo(), new LambdaUpdateWrapper<UserCharacterInfo>()
                .eq(UserCharacterInfo::getUserWorldId, message.getUserWorldId())
                .eq(UserCharacterInfo::getCharacterId, message.getCharacterId())
                .set(UserCharacterInfo::getLastChatTime, message.getTimestamp())
                .set(UserCharacterInfo::getLastChatContent, message.getContent()));
    }
}
