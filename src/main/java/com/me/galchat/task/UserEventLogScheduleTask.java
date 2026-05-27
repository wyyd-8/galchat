package com.me.galchat.task;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.constant.UserEventLogConstant;
import com.me.galchat.domain.dto.UserEventLogDelayTaskDTO;
import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.service.IUserEventLogService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
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

    private void offerDelayTask(EventTaskKey key, List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }

        UserEventLogDelayTaskDTO task = new UserEventLogDelayTaskDTO()
                .setUserWorldId(key.userWorldId())
                .setCharacterId(key.characterId())
                .setUserEventLogIds(eventIds);
        delayedQueue.offer(task, 0L, TimeUnit.MILLISECONDS);
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
