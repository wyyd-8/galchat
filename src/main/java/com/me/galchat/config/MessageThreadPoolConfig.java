package com.me.galchat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@Slf4j
public class MessageThreadPoolConfig {

    @Bean("delayTaskExecutor")
    public ThreadPoolTaskExecutor delayTaskExecutor() {
        log.info("初始化延时任务线程池...");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：消费延时任务不需要太多线程
        executor.setCorePoolSize(4);
        // 最大线程数
        executor.setMaxPoolSize(4);
        // 队列大小
        executor.setQueueCapacity(10);
        // 线程名前缀
        executor.setThreadNamePrefix("delay-task-handler-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        // 拒绝策略：由调用者线程执行，保证任务不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("bertTaskExecutor")
    public ThreadPoolTaskExecutor bertTaskExecutor() {
        log.info("初始化BERT完整性判断线程池...");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("bert-completion-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
