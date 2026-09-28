package com.me.galchat.service.impl.user;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Owns a dedicated retry timer without replacing Spring's scheduler for cron jobs. */
@Component
public class UserEventLogRetryScheduler {
    private final ThreadPoolTaskScheduler scheduler;

    public UserEventLogRetryScheduler() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("user-event-save-retry-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.initialize();
    }

    public void schedule(Runnable save) {
        scheduler.schedule(save, Instant.now().plus(Duration.ofSeconds(5)));
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
