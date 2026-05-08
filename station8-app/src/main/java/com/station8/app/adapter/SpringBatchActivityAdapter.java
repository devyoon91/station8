package com.station8.app.adapter;

import com.station8.engine.annotation.Activity;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 기존 Spring Batch ``Job``을 본 워크플로우 엔진의 액티비티(노드)로 호출하는 어댑터.
 *
 * 사용법: DAG 노드의 ``ACTIVITY_NM = "RUN_BATCH_JOB"``으로 두고
 * ``INPUT_PARAMS``에 다음 형식의 JSON을 둔다:
 *
 * <pre>{@code
 * {
 *   "jobName": "sampleBatchJob",
 *   "params": {
 *     "fileDate": "2026-05-07",
 *     "batchSize": "1000"
 *   }
 * }
 * }</pre>
 *
 * 결과:
 * <ul>
 *   <li>{@code BatchStatus.COMPLETED}: jobExecutionId/exitCode를 JSON으로 반환 → ``OUTPUT_DATA``에 기록</li>
 *   <li>{@code BatchStatus.FAILED}/예외: ``RuntimeException`` 으로 던져 우리 엔진의 재시도/DLQ 트리거</li>
 *   <li>그 외 ({@code STOPPED}, {@code ABANDONED}, {@code UNKNOWN}): 실패로 간주</li>
 * </ul>
 *
 * 멱등성/재시도: 동일 ``JobParameters``로 두 번 호출하면 Spring Batch는 새 ``JobInstance`` 생성을 거부하므로,
 * 우리 엔진의 ``RETRY_CNT``를 ``__retry__`` 파라미터로 자동 주입하여 재시도 시 새 ``JobInstance``를 만든다.
 *
 * 상태 추적 이중성:
 * <ul>
 *   <li>``BATCH_JOB_EXECUTION`` (Spring Batch 자체) — 청크/스텝 단위 상세 진행</li>
 *   <li>``H_WF_ACTIVITY_EXECUTION`` (본 엔진) — Job 호출 단위 상태</li>
 * </ul>
 * 두 테이블은 어댑터가 결과 JSON에 기록한 ``jobExecutionId``로 연결된다.
 */
@Component
public class SpringBatchActivityAdapter {

    private static final Logger log = LoggerFactory.getLogger(SpringBatchActivityAdapter.class);

    private final JobLauncher jobLauncher;
    private final JobRegistry jobRegistry;
    private final JsonUtil jsonUtil;

    public SpringBatchActivityAdapter(JobLauncher jobLauncher, JobRegistry jobRegistry, JsonUtil jsonUtil) {
        this.jobLauncher = jobLauncher;
        this.jobRegistry = jobRegistry;
        this.jsonUtil = jsonUtil;
    }

    @Activity(value = "RUN_BATCH_JOB", retryCount = 3, backoffSeconds = 60)
    public String runJob(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new IllegalArgumentException("RUN_BATCH_JOB: 입력 JSON이 비어있습니다");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> input = jsonUtil.fromJson(inputJson, Map.class);
        String jobName = (String) input.get("jobName");
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("RUN_BATCH_JOB: 'jobName' 누락 — input=" + inputJson);
        }

        Job job;
        try {
            job = jobRegistry.getJob(jobName);
        } catch (NoSuchJobException e) {
            throw new IllegalStateException("RUN_BATCH_JOB: 등록되지 않은 jobName=" + jobName, e);
        }

        JobParameters params = buildJobParameters(input);
        log.info("[RUN_BATCH_JOB] launching jobName={}, params={}", jobName, params);

        JobExecution exec;
        try {
            exec = jobLauncher.run(job, params);
        } catch (Exception e) {
            // JobInstance 충돌, 파라미터 검증 실패 등은 우리 엔진에서 재시도/DLQ로 처리되도록 RuntimeException으로 변환
            throw new RuntimeException("RUN_BATCH_JOB: jobLauncher.run 실패 jobName=" + jobName, e);
        }

        BatchStatus status = exec.getStatus();
        Long executionId = exec.getId();
        String exitCode = exec.getExitStatus().getExitCode();

        if (status == BatchStatus.COMPLETED) {
            log.info("[RUN_BATCH_JOB] completed jobName={}, executionId={}, exitCode={}",
                    jobName, executionId, exitCode);
            return jsonUtil.toJson(Map.of(
                    "jobName", jobName,
                    "jobExecutionId", String.valueOf(executionId),
                    "status", status.name(),
                    "exitCode", exitCode
            ));
        }

        // FAILED / STOPPED / ABANDONED / UNKNOWN — 우리 엔진 재시도/DLQ 흐름에 위임
        String exitDesc = exec.getExitStatus().getExitDescription();
        throw new RuntimeException(String.format(
                "RUN_BATCH_JOB: job=%s, executionId=%s, status=%s, exitCode=%s, exitDesc=%s",
                jobName, executionId, status, exitCode, exitDesc));
    }

    private JobParameters buildJobParameters(Map<String, Object> input) {
        JobParametersBuilder builder = new JobParametersBuilder();

        // v1: params 맵의 모든 entry를 String으로 전달 (단순화)
        Object paramsObj = input.get("params");
        if (paramsObj instanceof Map<?, ?> params) {
            for (Map.Entry<?, ?> e : params.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                builder.addString(e.getKey().toString(), e.getValue().toString());
            }
        }

        // 멱등성 회피: 우리 엔진의 retryCnt 또는 호출 시각을 unique 파라미터로 자동 주입
        // (동일 params로 두 번 호출하면 Spring Batch가 새 JobInstance를 거부하므로)
        Object retryCnt = input.get("__retry__");
        if (retryCnt != null) {
            builder.addString("__retry__", retryCnt.toString());
        } else {
            builder.addLong("__launch_ts__", System.currentTimeMillis());
        }

        return builder.toJobParameters();
    }
}
