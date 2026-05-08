package com.station8.app.adapter;

import com.station8.app.Application;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpringBatchActivityAdapter 통합 테스트 (@SpringBootTest로 BatchAutoConfiguration 활성).
 *
 * 시나리오:
 *  - 정상 실행 (sampleBatchJob, fail=false) → COMPLETED + JSON 결과
 *  - 강제 실패 (fail=true) → RuntimeException (엔진의 재시도/DLQ 트리거 경로)
 *  - 미등록 jobName → IllegalStateException
 *  - 멱등성: __retry__ 다른 값으로 두 번 호출 → 둘 다 성공 (서로 다른 JobInstance)
 */
@SpringBootTest(classes = Application.class)
class SpringBatchActivityAdapterTest {

    @Autowired
    SpringBatchActivityAdapter adapter;
    @Autowired
    JsonUtil jsonUtil;

    @Test
    void completed_returns_result_json() {
        String input = jsonUtil.toJson(Map.of(
                "jobName", "sampleBatchJob",
                "params", Map.of("fileDate", "2026-05-07", "fail", "false")
        ));
        String result = adapter.runJob(input);
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(result, Map.class);
        assertEquals("sampleBatchJob", parsed.get("jobName"));
        assertEquals("COMPLETED", parsed.get("status"));
        assertEquals("COMPLETED", parsed.get("exitCode"));
        assertNotNull(parsed.get("jobExecutionId"));
    }

    @Test
    void failed_throws_runtime_exception() {
        String input = jsonUtil.toJson(Map.of(
                "jobName", "sampleBatchJob",
                "params", Map.of("fileDate", "2026-05-07", "fail", "true")
        ));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> adapter.runJob(input));
        assertTrue(ex.getMessage().contains("RUN_BATCH_JOB"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FAILED") || ex.getMessage().contains("status=FAILED"),
                "메시지에 BatchStatus.FAILED 포함되어야 함: " + ex.getMessage());
    }

    @Test
    void unknown_job_throws_IllegalStateException() {
        String input = jsonUtil.toJson(Map.of(
                "jobName", "noSuchJob",
                "params", Map.of()
        ));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> adapter.runJob(input));
        assertTrue(ex.getMessage().contains("noSuchJob"), ex.getMessage());
    }

    @Test
    void retry_with_different_retry_counter_creates_new_instance() {
        String input1 = jsonUtil.toJson(Map.of(
                "jobName", "sampleBatchJob",
                "__retry__", "1",
                "params", Map.of("fileDate", "2026-05-07")
        ));
        String input2 = jsonUtil.toJson(Map.of(
                "jobName", "sampleBatchJob",
                "__retry__", "2",
                "params", Map.of("fileDate", "2026-05-07")
        ));
        String r1 = adapter.runJob(input1);
        String r2 = adapter.runJob(input2);
        assertNotNull(r1);
        assertNotNull(r2);

        @SuppressWarnings("unchecked")
        Map<String, Object> p1 = jsonUtil.fromJson(r1, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> p2 = jsonUtil.fromJson(r2, Map.class);
        assertNotEquals(p1.get("jobExecutionId"), p2.get("jobExecutionId"),
                "재시도 카운터가 다르면 별개의 JobExecution 이어야 함");
    }

    @Test
    void blank_input_throws() {
        assertThrows(IllegalArgumentException.class, () -> adapter.runJob(""));
        assertThrows(IllegalArgumentException.class, () -> adapter.runJob(null));
    }

    @Test
    void missing_jobName_throws() {
        String input = jsonUtil.toJson(Map.of("params", Map.of("k", "v")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> adapter.runJob(input));
        assertTrue(ex.getMessage().contains("jobName"), ex.getMessage());
    }
}
