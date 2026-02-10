package com.bangrang.workflow.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 워크플로우 엔진을 위한 스케줄링 및 스레드 풀 설정.
 */
@Configuration
@EnableScheduling
public class WorkflowEngineConfig {

    /**
     * 액티비티 실행을 담당하는 스레드 풀 설정.
     */
    @Bean
    public ThreadPoolTaskExecutor workflowTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("wf-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ExponentialBackoffRetryPolicy retryPolicy() {
        return new ExponentialBackoffRetryPolicy();
    }

    @Bean
    public TaskExecutor taskExecutor(ActivityRepository activityRepository, JsonUtil jsonUtil) {
        return new JdbcTaskExecutor(activityRepository, jsonUtil);
    }
}

