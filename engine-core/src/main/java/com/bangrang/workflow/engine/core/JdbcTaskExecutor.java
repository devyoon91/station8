package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.util.JsonUtil;
import com.bangrang.workflow.engine.exception.ErrorCodes;
import com.bangrang.workflow.engine.exception.WorkflowEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * TaskExecutor의 JDBC 기반 기본 구현체.
 * - 현재 액티비티의 성공/실패 결과를 DB(H_WF_ACTIVITY_EXECUTION)에 반영
 * - 컨텍스트에 지정된 다음 액티비티가 있으면 PENDING으로 생성하여 오케스트레이션
 */
public class JdbcTaskExecutor implements TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskExecutor.class);

    private static final String CTX_KEY_EXECUTION_ID = "executionId"; // WorkflowWorker가 컨텍스트에 주입해야 함
    private static final String CTX_KEY_INSTANCE_ID = "instanceId";   // 필요 시 주입(없으면 context.instanceId() 사용)

    private final ActivityRepository activityRepository;
    private final JsonUtil jsonUtil;

    public JdbcTaskExecutor(ActivityRepository activityRepository, JsonUtil jsonUtil) {
        this.activityRepository = activityRepository;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public void executeCurrent(WorkflowContext context) {
        // 실제 비즈니스 메서드 호출은 WorkflowWorker/Invoker 레이어에서 수행.
        // 본 구현체는 실행 결과를 반영하고 다음 단계 오케스트레이션에 해당하므로, 여기서는 별도 동작 없음.
        log.debug("executeCurrent called for activity: {}", context.currentActivityName());
    }

    @Override
    public void scheduleNext(WorkflowContext context, String nextActivityName, Object input) {
        String instanceId = resolveInstanceId(context);
        String inputJson = jsonUtil.toJson(input);
        activityRepository.createPending(instanceId, nextActivityName, inputJson, null);
        log.info("Scheduled next activity '{}' for instance {}", nextActivityName, instanceId);
    }

    @Override
    public void complete(WorkflowContext context, Object output) {
        String executionId = resolveExecutionId(context);
        String outputJson = jsonUtil.toJson(output);
        ActivityExecution updated = new ActivityExecution(
            executionId,
            context.instanceId(),
            null, // nodeId: updateStatus는 NODE_ID를 변경하지 않음 (PK 기준 SET 항목만 갱신)
            context.currentActivityName(),
            "COMPLETED",
            null, // inputData
            outputJson,
            null, // errorMessage
            null, // stackTrace
            0,
            null, // nextRetryDt
            null, // startDt
            LocalDateTime.now(), // endDt
            null, null, null, // useFl, viewFl, delFl
            null, null, // regDt, regId
            LocalDateTime.now(), "engine" // editDt, editId
        );
        activityRepository.updateStatus(updated);

        // 컨텍스트에 next 힌트가 존재하면 레거시(선형) 오케스트레이션
        // DAG 모드에서는 WorkflowWorker가 인터프리터에 onNodeCompleted를 위임함
        context.nextActivityName().ifPresent(name -> {
            Object nextInput = context.nextActivityInput().orElse(null);
            scheduleNext(context, name, nextInput);
        });
    }

    @Override
    public void fail(WorkflowContext context, Throwable error, Duration nextBackoff) {
        String executionId = resolveExecutionId(context);
        String errorMessage = error.getMessage();
        String stackTrace = buildStackTrace(error);
        LocalDateTime nextRetry = (nextBackoff != null) ? LocalDateTime.now().plusSeconds(nextBackoff.getSeconds()) : null;

        ActivityExecution updated = new ActivityExecution(
            executionId,
            context.instanceId(),
            null, // nodeId: updateStatus는 NODE_ID를 변경하지 않음
            context.currentActivityName(),
            "FAILED",
            null, // inputData
            null, // outputData
            errorMessage,
            stackTrace,
            context.attempt(),
            nextRetry,
            null, // startDt
            LocalDateTime.now(), // endDt
            null, null, null, // useFl, viewFl, delFl
            null, null, // regDt, regId
            LocalDateTime.now(), "engine" // editDt, editId
        );
        activityRepository.updateStatus(updated);

        // 재시도 정책은 여기서 직접 생성하지 않음(정책 해석은 상위 레이어에서).
        // 필요 시 다음과 같이 동일 액티비티 재시도 레코드를 생성할 수 있음:
        if (nextRetry != null) {
            // #49 fix: context.input()이 이미 String이면 그대로 사용 (jsonUtil.toJson은 String을 또 escape 처리해
            // 매 retry마다 escape가 누적됨). String이 아닐 때만 직렬화한다.
            Object input = context.input();
            String inputJson = (input instanceof String s) ? s : jsonUtil.toJson(input);
            activityRepository.createPending(context.instanceId(), context.currentActivityName(), inputJson, nextRetry);
        }
    }

    @Override
    public void checkpoint(WorkflowContext context, Object stateSnapshot) {
        // 체크포인트는 인스턴스 레벨( U_WF_INSTANCE.STATE_DATA )에 저장되며,
        // 본 구현에서는 컨텍스트가 보유한 스냅샷 직렬화 기능(saveState)을 사용한다.
        context.saveState(stateSnapshot);
    }

    private String resolveExecutionId(WorkflowContext context) {
        return Optional.ofNullable(context.attributes().get(CTX_KEY_EXECUTION_ID))
                .map(Object::toString)
                .orElseThrow(() -> new WorkflowEngineException(ErrorCodes.CONTEXT_ATTRIBUTE_MISSING, "Missing executionId in WorkflowContext.attributes"));
    }

    private String resolveInstanceId(WorkflowContext context) {
        Object v = context.attributes().get(CTX_KEY_INSTANCE_ID);
        return v != null ? v.toString() : context.instanceId();
    }

    private String buildStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append("\n");
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\tat ").append(e).append("\n");
        }
        return sb.toString();
    }
}

