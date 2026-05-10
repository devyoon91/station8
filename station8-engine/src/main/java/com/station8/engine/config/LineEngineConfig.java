package com.station8.engine.config;

import com.station8.engine.core.DlqNotifier;
import com.station8.engine.core.ExponentialBackoffRetryPolicy;
import com.station8.engine.core.JdbcTaskExecutor;
import com.station8.engine.core.SlaNotifier;
import com.station8.engine.core.TaskExecutor;
import com.station8.engine.core.WebhookDlqNotifier;
import com.station8.engine.core.WebhookSlaNotifier;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 라인 엔진을 위한 스케줄링 및 스레드 풀 설정.
 */
@Configuration
@EnableScheduling
public class LineEngineConfig {

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

    /**
     * DLQ 웹훅 알림 발송기. engine.dlq.webhook-url 프로퍼티로 URL을 설정합니다.
     * URL이 비어있으면 콘솔 로그로 대체됩니다.
     */
    @Bean
    public DlqNotifier dlqNotifier(@Value("${engine.dlq.webhook-url:}") String webhookUrl, JsonUtil jsonUtil) {
        return new WebhookDlqNotifier(webhookUrl, jsonUtil);
    }

    /**
     * #138 — SLA 위반 알림 발송기. engine.sla.webhook-url 프로퍼티로 URL을 설정합니다.
     * URL이 비어있으면 콘솔 WARN 로그로 대체됩니다. 인스턴스 RunOptions의 notificationWebhookUrl로 override 가능.
     */
    @Bean
    public SlaNotifier slaNotifier(@Value("${engine.sla.webhook-url:}") String webhookUrl, JsonUtil jsonUtil) {
        return new WebhookSlaNotifier(webhookUrl, jsonUtil);
    }
}

