package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTaskExecutorTest {

    private StubActivityRepository activityRepository;
    private JsonUtil jsonUtil;
    private JdbcTaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        activityRepository = new StubActivityRepository();
        taskExecutor = new JdbcTaskExecutor(activityRepository, jsonUtil);
    }

    private DefaultLineContext createContext(String instanceId, String activityName, int attempt, Object input) {
        return createContext(instanceId, activityName, null, attempt, input);
    }

    /** #278 — DAG 모드 회귀 가드용: nodeId 명시 가능. */
    private DefaultLineContext createContext(String instanceId, String activityName, String nodeId,
                                              int attempt, Object input) {
        var ctx = new DefaultLineContext(instanceId, "TestWF", activityName, nodeId, attempt, input, null, jsonUtil);
        ctx.attributes().put("executionId", "exec-001");
        ctx.attributes().put("instanceId", instanceId);
        return ctx;
    }

    @Test
    @DisplayName("scheduleNext: PENDING 상태의 다음 액티비티를 생성한다")
    void scheduleNext_createsPending() {
        var context = createContext("inst-001", "step1", 1, null);
        Map<String, String> nextInput = Map.of("key", "value");

        taskExecutor.scheduleNext(context, "step2", nextInput);

        assertEquals(1, activityRepository.pendingCalls.size());
        var call = activityRepository.pendingCalls.getFirst();
        assertEquals("inst-001", call.instanceId);
        assertEquals("step2", call.activityName);
        assertTrue(call.inputData.contains("\"key\""));
        assertNull(call.nextRetryDt);
    }

    @Test
    @DisplayName("complete: 상태를 COMPLETED로 업데이트한다")
    void complete_updatesStatusToCompleted() {
        var context = createContext("inst-001", "step1", 1, null);

        taskExecutor.complete(context, Map.of("result", "ok"));

        assertEquals(1, activityRepository.updatedExecutions.size());
        ActivityExecution updated = activityRepository.updatedExecutions.getFirst();
        assertEquals("exec-001", updated.id());
        assertEquals("COMPLETED", updated.statusSt());
        assertNotNull(updated.outputData());
        assertTrue(updated.outputData().contains("\"result\""));
    }

    @Test
    @DisplayName("complete: 컨텍스트에 next 힌트가 있으면 다음 액티비티를 스케줄링한다")
    void complete_schedulesNextWhenHintPresent() {
        var context = createContext("inst-001", "step1", 1, null);
        context.setNext("step2", "nextInput");

        taskExecutor.complete(context, "output");

        assertEquals(1, activityRepository.updatedExecutions.size());
        assertEquals(1, activityRepository.pendingCalls.size());
        assertEquals("step2", activityRepository.pendingCalls.getFirst().activityName);
    }

    @Test
    @DisplayName("complete: next 힌트가 없으면 다음 액티비티를 스케줄링하지 않는다")
    void complete_doesNotScheduleNextWhenNoHint() {
        var context = createContext("inst-001", "step1", 1, null);

        taskExecutor.complete(context, "output");

        assertEquals(1, activityRepository.updatedExecutions.size());
        assertTrue(activityRepository.pendingCalls.isEmpty());
    }

    @Test
    @DisplayName("fail: 상태를 FAILED로 업데이트하고 재시도 레코드를 생성한다")
    void fail_updatesStatusAndCreatesRetry() {
        var context = createContext("inst-001", "step1", 1, "myInput");
        RuntimeException error = new RuntimeException("test error");
        Duration backoff = Duration.ofSeconds(30);

        taskExecutor.fail(context, error, backoff);

        assertEquals(1, activityRepository.updatedExecutions.size());
        ActivityExecution updated = activityRepository.updatedExecutions.getFirst();
        assertEquals("FAILED", updated.statusSt());
        assertEquals("test error", updated.errorMessage());
        assertNotNull(updated.stackTrace());
        assertEquals(1, updated.retryCnt());

        // 재시도 레코드 생성 확인
        assertEquals(1, activityRepository.pendingCalls.size());
        var call = activityRepository.pendingCalls.getFirst();
        assertEquals("inst-001", call.instanceId);
        assertEquals("step1", call.activityName);
        assertNotNull(call.nextRetryDt);
    }

    @Test
    @DisplayName("#49 fix: fail이 String input을 다시 직렬화하지 않는다 (escape 누적 방지)")
    void fail_doesNotReSerializeStringInput() {
        // 이미 직렬화된 JSON String을 input으로 받은 케이스 — 워커가 PENDING activity의 inputData를 그대로 넘긴 상황
        String alreadySerialized = "{\"id\":\"2\",\"content\":\"Second Data\"}";
        var context = createContext("inst-001", "step1", 1, alreadySerialized);
        RuntimeException error = new RuntimeException("forced failure");

        taskExecutor.fail(context, error, Duration.ofSeconds(5));

        assertEquals(1, activityRepository.pendingCalls.size());
        var call = activityRepository.pendingCalls.getFirst();
        // 핵심: 원본 JSON 문자열이 그대로 보존되어야 함 (또 한 번 직렬화되어 \" → \\\" 가 되면 누적의 시작)
        assertEquals(alreadySerialized, call.inputData,
                "이미 String JSON인 input은 그대로 보존되어야 함 (이중 직렬화 금지)");
    }

    // ---- #278 — retry preserves nodeId ----

    @Test
    @DisplayName("#278: DAG 모드 활동의 retry는 원본 nodeId를 보존한다")
    void fail_dagMode_retryPreservesNodeId() {
        // DAG 모드 — context에 nodeId 명시
        var context = createContext("inst-001", "MIGRATE", "node-A", 1, "{\"id\":\"x\"}");
        taskExecutor.fail(context, new RuntimeException("err"), Duration.ofSeconds(5));

        assertEquals(1, activityRepository.pendingCalls.size());
        var call = activityRepository.pendingCalls.getFirst();
        assertEquals("node-A", call.nodeId,
                "DAG retry는 원본 nodeId 보존해야 — 이전엔 NULL로 박혀 fan-out 차단 + row 누적");
    }

    @Test
    @DisplayName("#278: legacy/linear 모드 retry는 nodeId=null 그대로")
    void fail_legacyMode_retryWithNullNodeId() {
        // legacy 모드 — context의 nodeId가 null
        var context = createContext("inst-001", "step1", null, 1, null);
        taskExecutor.fail(context, new RuntimeException("err"), Duration.ofSeconds(5));

        assertEquals(1, activityRepository.pendingCalls.size());
        var call = activityRepository.pendingCalls.getFirst();
        assertNull(call.nodeId, "legacy/linear 모드는 nodeId=null 그대로 (회귀 가드)");
    }

    @Test
    @DisplayName("#49 fix: fail이 비-String input은 직렬화한다")
    void fail_serializesNonStringInput() {
        Map<String, String> input = Map.of("id", "1", "content", "First");
        var context = createContext("inst-001", "step1", 1, input);
        RuntimeException error = new RuntimeException("err");

        taskExecutor.fail(context, error, Duration.ofSeconds(5));

        assertEquals(1, activityRepository.pendingCalls.size());
        var call = activityRepository.pendingCalls.getFirst();
        assertNotNull(call.inputData);
        assertTrue(call.inputData.contains("\"id\""), "Map은 JSON으로 직렬화되어야 함");
        assertTrue(call.inputData.contains("\"First\""));
        // String이 아닌 Map이라 toJson을 거쳐야 하므로 결과는 valid JSON object
        assertTrue(call.inputData.startsWith("{"));
    }

    @Test
    @DisplayName("#49 fix: 동일 input으로 여러 번 fail 호출해도 escape가 누적되지 않는다")
    void fail_repeatedRetryDoesNotAccumulateEscape() {
        String original = "{\"k\":\"v\"}";
        var context = createContext("inst-001", "step1", 1, original);
        RuntimeException error = new RuntimeException("err");

        // 5번 fail 호출 (5회 재시도 시뮬레이션)
        for (int i = 0; i < 5; i++) {
            taskExecutor.fail(context, error, Duration.ofSeconds(5));
        }

        assertEquals(5, activityRepository.pendingCalls.size());
        // 모든 호출에서 inputData가 원본 그대로 (escape 누적 0)
        for (var call : activityRepository.pendingCalls) {
            assertEquals(original, call.inputData,
                    "매 retry마다 input이 원본 그대로여야 함 (escape 누적 금지)");
        }
    }

    @Test
    @DisplayName("fail: nextBackoff가 null이면 재시도 레코드를 생성하지 않는다")
    void fail_noRetryWhenBackoffNull() {
        var context = createContext("inst-001", "step1", 3, "myInput");
        RuntimeException error = new RuntimeException("final failure");

        taskExecutor.fail(context, error, null);

        assertEquals(1, activityRepository.updatedExecutions.size());
        assertTrue(activityRepository.pendingCalls.isEmpty());
    }

    @Test
    @DisplayName("checkpoint: 컨텍스트의 saveState를 호출한다")
    void checkpoint_savesState() {
        var context = createContext("inst-001", "step1", 1, null);

        taskExecutor.checkpoint(context, Map.of("progress", 75));

        assertNotNull(context.getStateSnapshotJson());
        assertTrue(context.getStateSnapshotJson().contains("75"));
    }

    // --- Stub 구현 ---

    record PendingCall(String instanceId, String nodeId, String activityName, String inputData, LocalDateTime nextRetryDt) {}

    static class StubActivityRepository implements ActivityRepository {
        final List<ActivityExecution> updatedExecutions = new ArrayList<>();
        final List<PendingCall> pendingCalls = new ArrayList<>();

        @Override
        public List<ActivityExecution> findPendingActivitiesWithLock(int limit) {
            return List.of();
        }

        @Override
        public void updateStatus(ActivityExecution activityExecution) {
            updatedExecutions.add(activityExecution);
        }

        @Override
        public String createPending(String instanceId, String nodeId, String activityName, String inputData, LocalDateTime nextRetryDt) {
            pendingCalls.add(new PendingCall(instanceId, nodeId, activityName, inputData, nextRetryDt));
            return "stub-id-" + pendingCalls.size();
        }

        @Override
        public String createForNode(String instanceId, String nodeId, String activityName, String statusSt, String inputData) {
            return "stub-node-id";
        }

        @Override
        public ActivityExecution findById(String executionId) {
            return null;
        }

        @Override
        public ActivityExecution findByInstanceAndNode(String instanceId, String nodeId) {
            return null;
        }

        @Override
        public void promoteToPending(String executionId) {
        }

        @Override
        public List<LineInstance> findAllInstances() {
            return List.of();
        }

        @Override
        public List<LineInstance> findInstancesPage(
                com.station8.engine.repository.InstanceQueryFilter filter, int offset, int limit) {
            return List.of();
        }

        @Override
        public long countInstances(com.station8.engine.repository.InstanceQueryFilter filter) {
            return 0L;
        }

        @Override
        public java.util.Map<String, Long> countInstancesByStatus() {
            return java.util.Map.of();
        }

        @Override
        public LineInstance findInstanceById(String instanceId) {
            return null;
        }

        @Override
        public List<ActivityExecution> findActivitiesByInstanceId(String instanceId) {
            return List.of();
        }

        @Override
        public void resetToPending(String executionId) {
        }

        @Override
        public int bulkUpdateNotStartedStatuses(String instanceId, String toStatus) {
            return 0;
        }

        // #164 — Pipeline 게이트용 — 본 stub은 모두 false/no-op
        @Override
        public boolean isNodeCompleted(String instanceId, String nodeId) {
            return false;
        }

        @Override
        public boolean isAnyNodeStarted(String instanceId, java.util.Collection<String> nodeIds) {
            return false;
        }

        @Override
        public void revertGateBlocked(String executionId, LocalDateTime nextRetryDt) {
        }
    }
}
