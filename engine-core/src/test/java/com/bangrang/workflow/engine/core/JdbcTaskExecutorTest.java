package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.entity.WorkflowInstance;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.util.JsonUtil;
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

    private DefaultWorkflowContext createContext(String instanceId, String activityName, int attempt, Object input) {
        var ctx = new DefaultWorkflowContext(instanceId, "TestWF", activityName, attempt, input, null, jsonUtil);
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

    record PendingCall(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt) {}

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
        public void createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt) {
            pendingCalls.add(new PendingCall(instanceId, activityName, inputData, nextRetryDt));
        }

        @Override
        public List<WorkflowInstance> findAllInstances() {
            return List.of();
        }

        @Override
        public WorkflowInstance findInstanceById(String instanceId) {
            return null;
        }

        @Override
        public List<ActivityExecution> findActivitiesByInstanceId(String instanceId) {
            return List.of();
        }

        @Override
        public void resetToPending(String executionId) {
        }
    }
}
