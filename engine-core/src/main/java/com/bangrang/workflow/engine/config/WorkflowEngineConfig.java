package com.bangrang.workflow.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * ?뚰겕?뚮줈???붿쭊???꾪븳 ?ㅼ?以꾨쭅 諛??ㅻ젅??? ?ㅼ젙.
 */
@Configuration
@EnableScheduling
public class WorkflowEngineConfig {

    /**
     * ?≫떚鍮꾪떚 ?ㅽ뻾???대떦?섎뒗 ?ㅻ젅??? ?ㅼ젙.
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

