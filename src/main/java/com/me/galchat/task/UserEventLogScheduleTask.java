package com.me.galchat.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.constant.UserEventLogConstant;
import com.me.galchat.domain.dto.UserEventLogDelayTaskDTO;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.service.IUserEventLogService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserEventLogScheduleTask {

    private final IUserEventLogService userEventLogService;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final RedissonClient redissonClient;

    private RDelayedQueue<UserEventLogDelayTaskDTO> delayedQueue;

    @PostConstruct
    public void init() {
        RBlockingQueue<UserEventLogDelayTaskDTO> blockingQueue = redissonClient.getBlockingQueue(
                RedisConstant.USER_EVENT_LOG_DELAY_QUEUE_NAME, new JsonJacksonCodec());
        delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
    }

    @Scheduled(cron = UserEventLogConstant.SCHEDULE_CRON, zone = UserEventLogConstant.SCHEDULE_ZONE)
    public void scheduleUpcomingUserEventLogs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextSearchTime = nextSearchTime(now);
        List<UserEventLog> eventLogs = userEventLogService.listUpcomingUserEventLogs(now, nextSearchTime);
        if (eventLogs.isEmpty()) {
            log.info("用户事件定时扫描完成, begin:{}, end:{}, count:0", now, nextSearchTime);
            return;
        }

        Map<EventTaskKey, List<Long>> eventIdsByTask = eventLogs.stream()
                .collect(Collectors.groupingBy(this::eventTaskKey,
                        Collectors.mapping(UserEventLog::getId, Collectors.toList())));
        eventIdsByTask.forEach(this::offerDelayTask);
        log.info("用户事件定时扫描完成, begin:{}, end:{}, eventCount:{}, taskCount:{}",
                now, nextSearchTime, eventLogs.size(), eventIdsByTask.size());
    }

    @Scheduled(cron = UserEventLogConstant.DAILY_CARE_SCHEDULE_CRON, zone = UserEventLogConstant.SCHEDULE_ZONE)
    public void scheduleDailyUserEventCare() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dayBegin = LocalDateTime.of(now.toLocalDate(), LocalTime.MIN);
        LocalDateTime nextDayBegin = LocalDateTime.now();
        List<UserEventLog> eventLogs = userEventLogService.listUpcomingUserEventLogs(dayBegin, nextDayBegin);
        Map<EventTaskKey, List<Long>> eventIdsByTask = eventLogs.stream()
                .collect(Collectors.groupingBy(this::eventTaskKey,
                        Collectors.mapping(UserEventLog::getId, Collectors.toList())));
        eventIdsByTask.forEach((key, eventIds) -> offerDelayTask(key, eventIds, UserEventLogConstant.TASK_TYPE_DAILY_CARE));

        List<EventTaskKey> chattedTaskKeys = listChattedTaskKeys(dayBegin, nextDayBegin);
        long discussionTaskCount = 0;
        for (EventTaskKey key : chattedTaskKeys) {
            if (eventIdsByTask.containsKey(key)) {
                continue;
            }
            offerDelayTask(key, List.of(), UserEventLogConstant.TASK_TYPE_DAILY_CARE);
            discussionTaskCount++;
        }
        log.info("用户事件每日关怀扫描完成, begin:{}, end:{}, eventCount:{}, eventTaskCount:{}, discussionTaskCount:{}",
                dayBegin, nextDayBegin, eventLogs.size(), eventIdsByTask.size(), discussionTaskCount);
    }

    private void offerDelayTask(EventTaskKey key, List<Long> eventIds) {
        offerDelayTask(key, eventIds, UserEventLogConstant.TASK_TYPE_UPCOMING);
    }

    private void offerDelayTask(EventTaskKey key, List<Long> eventIds, String taskType) {
        if (eventIds.isEmpty() && !UserEventLogConstant.TASK_TYPE_DAILY_CARE.equals(taskType)) {
            return;
        }

        UserEventLogDelayTaskDTO task = new UserEventLogDelayTaskDTO()
                .setTaskType(taskType)
                .setUserWorldId(key.userWorldId())
                .setCharacterId(key.characterId())
                .setUserEventLogIds(eventIds);
        delayedQueue.offer(task, 0L, TimeUnit.MILLISECONDS);
    }

    private List<EventTaskKey> listChattedTaskKeys(LocalDateTime beginTime, LocalDateTime endTime) {
        if (beginTime == null || endTime == null || !beginTime.isBefore(endTime)) {
            return List.of();
        }

        return userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                        .select(UserChatHistory::getUserWorldId, UserChatHistory::getCharacterId)
                        .isNotNull(UserChatHistory::getUserWorldId)
                        .isNotNull(UserChatHistory::getCharacterId)
                        .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                                .or()
                                .eq(UserChatHistory::getType, MessageType.USER.getValue()))
                        .ge(UserChatHistory::getTimestamp, beginTime)
                        .lt(UserChatHistory::getTimestamp, endTime)
                        .groupBy(UserChatHistory::getUserWorldId, UserChatHistory::getCharacterId))
                .stream()
                .map(history -> new EventTaskKey(history.getUserWorldId(), history.getCharacterId()))
                .toList();
    }

    private EventTaskKey eventTaskKey(UserEventLog eventLog) {
        return new EventTaskKey(eventLog.getUserWorldId(), eventLog.getCharacterId());
    }

    private LocalDateTime nextSearchTime(LocalDateTime now) {
        LocalTime currentTime = now.toLocalTime();
        for (LocalTime searchTime : UserEventLogConstant.SEARCH_TIMES) {
            if (currentTime.isBefore(searchTime)) {
                return LocalDateTime.of(now.toLocalDate(), searchTime);
            }
        }
        return LocalDateTime.of(now.toLocalDate().plusDays(1), UserEventLogConstant.SEARCH_TIMES.getFirst());
    }

    private record EventTaskKey(Long userWorldId, Long characterId) {
    }
}
