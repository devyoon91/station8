package com.example.workflow.engine.config;

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
        executor.setCorePoolSize(10);        // 기본 스레드 수
        executor.setMaxPoolSize(20);         // 최대 스레드 수
        executor.setQueueCapacity(500);      // 대기 큐 용량
        executor.setThreadNamePrefix("wf-worker-");
        
        // 큐가 가득 찼을 때 호출한 스레드에서 직접 실행 (Backpressure 제어)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.initialize();
        return executor;
    }
}
