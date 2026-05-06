package com.bangrang.workflow.app.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 어댑터 검증/시연용 샘플 Spring Batch Job.
 * <ul>
 *   <li>``sampleBatchJob`` — 단일 tasklet. JobParameter ``fail=true`` 일 때 강제 실패하여 어댑터의 실패 경로 검증</li>
 * </ul>
 */
@Configuration
public class SampleBatchJobConfig {

    private static final Logger log = LoggerFactory.getLogger(SampleBatchJobConfig.class);

    @Bean
    public Job sampleBatchJob(JobRepository jobRepository, Step sampleStep) {
        return new JobBuilder("sampleBatchJob", jobRepository)
                .start(sampleStep)
                .build();
    }

    @Bean
    public Step sampleStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("sampleStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    var params = chunkContext.getStepContext().getJobParameters();
                    Object fileDate = params.get("fileDate");
                    Object fail = params.get("fail");
                    log.info("[sampleBatchJob] running: fileDate={}, retry={}, launchTs={}",
                            fileDate,
                            params.get("__retry__"),
                            params.get("__launch_ts__"));
                    if ("true".equalsIgnoreCase(String.valueOf(fail))) {
                        throw new RuntimeException("Forced failure for adapter test (fail=true)");
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }
}
