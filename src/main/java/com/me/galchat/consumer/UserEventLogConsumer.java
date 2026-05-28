package com.me.galchat.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.constant.UserEventLogConstant;
import com.me.galchat.domain.dto.UserEventLogDelayTaskDTO;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.service.IUserCharacterInfoService;
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
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final IUserCharacterInfoService userCharacterInfoService;

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

        UserCharacterInfo userCharacterInfo = userCharacterInfoService.getOne(
                new LambdaQueryWrapper<UserCharacterInfo>()
                        .eq(UserCharacterInfo::getUserWorldId, task.getUserWorldId())
                        .eq(UserCharacterInfo::getCharacterId, task.getCharacterId()));
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

        String prompt = userCharacterInfoService.buildCharacterPrompt(task.getUserWorldId(), task.getCharacterId());

        String content;
        if (task.getTaskType().equals(UserEventLogConstant.TASK_TYPE_UPCOMING)) {
            content = userEventCareClient.prompt()
                    .system("""
                        你是一个专业的主动关怀消息生成机器人，需要扮演下文中给定的角色，为用户生成一条关怀消息。
                        你会收到同一用户和同一角色之间的一组用户事件，每条事件包含时间和描述。
                        请依据这些事件生成一条自然、简短的关怀消息，像角色主动发来的聊天内容。
                        主要围绕提醒即将发生的事件。
                        生成的消息需要符合角色性格，当你觉得不合适发出消息时，请返回空字符串。
                        消息需要把多个事件自然融合，不要逐条罗列，不要提到“事件记录”“数据库”“任务”等系统概念。
                        只输出最终要发送给用户的一条消息，不要输出解释、Markdown 或其他内容。""" + prompt)
                    .user(formatUserEventCarePrompt(eventLogs))
                    .call()
                    .content();
        } else if (task.getTaskType().equals(UserEventLogConstant.TASK_TYPE_DAILY_CARE)) {
            content = userEventCareClient.prompt()
                    .system("""
                        你是一个专业的主动关怀消息生成机器人，需要扮演下文中给定的角色，为用户生成一条关怀消息。
                        你会收到同一用户和同一角色之间的一组用户事件，每条事件包含时间和描述。
                        现在已经是夜间了，给定的事件都是今天用户所发生的事件。
                        请依据这些事件生成一条自然、简短的关怀消息，像角色主动发来的聊天内容。
                        主要围绕回顾这一天的事件。
                        生成的消息需要符合角色性格，当你觉得不合适发出消息时，请返回空字符串。
                        消息需要把多个事件自然融合，不要逐条罗列，不要提到“事件记录”“数据库”“任务”等系统概念。
                        只输出最终要发送给用户的一条消息，不要输出解释、Markdown 或其他内容。""" + prompt)
                    .user(formatUserEventCarePrompt(eventLogs))
                    .call()
                    .content();
        } else {
            content = null;
        }
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
        userCharacterInfoService.update(new UserCharacterInfo(), new LambdaUpdateWrapper<UserCharacterInfo>()
                .eq(UserCharacterInfo::getUserWorldId, message.getUserWorldId())
                .eq(UserCharacterInfo::getCharacterId, message.getCharacterId())
                .set(UserCharacterInfo::getLastChatTime, message.getTimestamp())
                .set(UserCharacterInfo::getLastChatContent, message.getContent()));
    }
}
