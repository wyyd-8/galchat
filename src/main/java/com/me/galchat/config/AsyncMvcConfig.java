package com.me.galchat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncMvcConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 注入我们自定义的线程池
        configurer.setTaskExecutor(mvcTaskExecutor());
        // 可选：设置全局异步请求超时时间（毫秒）
        configurer.setDefaultTimeout(60000);
    }

    @Bean
    public AsyncTaskExecutor mvcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数，即使空闲也会保留
        executor.setCorePoolSize(10);
        // 最大线程数，当任务队列满后可以额外创建的线程数
        executor.setMaxPoolSize(50);
        // 缓冲队列容量，用于存放等待执行的任务
        executor.setQueueCapacity(200);
        // 允许空闲线程的存活时间（秒）
        executor.setKeepAliveSeconds(60);
        // 给线程池中的线程命名一个便于调试的前缀
        executor.setThreadNamePrefix("spring-mvc-async-");
        // 生产环境推荐 CallerRunsPolicy 拒绝策略，保证系统稳定[reference:4]
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 等待所有任务结束后再关闭应用
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 初始化线程池
        executor.initialize();
        return executor;
    }
}