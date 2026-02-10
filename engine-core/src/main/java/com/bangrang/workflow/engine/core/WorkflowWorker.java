package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DB에서 실행 대기 중인 작업을 가져와 스레드 풀에서 실행하는 워커 클래스.
 * Spring의 @Scheduled를 이용해 주기적으로 폴링하며, ThreadPoolTaskExecutor를 통해 동시성을 제어합니다.
 */
@Component
public class WorkflowWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

    private final ActivityRepository activityRepository;
    private final TaskExecutor taskExecutor;
    private final ThreadPoolTaskExecutor workflowTaskExecutor;
    private final WorkflowRegistry workflowRegistry;
    private final ExponentialBackoffRetryPolicy retryPolicy;

    public WorkflowWorker(ActivityRepository activityRepository, 
                          TaskExecutor taskExecutor,
                          ThreadPoolTaskExecutor workflowTaskExecutor,
                          WorkflowRegistry workflowRegistry,
                          ExponentialBackoffRetryPolicy retryPolicy) {
        this.activityRepository = activityRepository;
        this.taskExecutor = taskExecutor;
        this.workflowTaskExecutor = workflowTaskExecutor;
        this.workflowRegistry = workflowRegistry;
        this.retryPolicy = retryPolicy;
    }

    /**
     * 주기적으로 DB를 폴링하여 처리 가능한 작업을 가져옵니다.
     * (예: 1초마다 실행)
     */
    @Scheduled(fixedDelayString = "${workflow.polling.interval-ms:1000}")
    public void pollActivities() {
        log.trace("Polling pending activities...");
        
        // 한 번의 폴링에서 가져올 최대 작업 수
        int limit = 10; 
        List<ActivityExecution> pendingActivities = activityRepository.findPendingActivitiesWithLock(limit);

        for (ActivityExecution activity : pendingActivities) {
            log.info("Dispatching activity: {} (Instance ID: {})", activity.activityName(), activity.instanceId());
            
            // 별도의 스레드 풀에서 비동기 실행
            workflowTaskExecutor.execute(() -> processActivity(activity));
        }
    }

    /**
     * 개별 액티비티를 실행하고 결과를 업데이트하는 실제 로직.
     */
    private void processActivity(ActivityExecution activity) {
        // 1. 레지스트리에서 액티비티 메타데이터 조회
        WorkflowRegistry.ActivityMetadata metadata = workflowRegistry.getActivity(activity.activityName());
        if (metadata == null) {
            log.error("No registered activity found for name: {}", activity.activityName());
            updateActivityAsFailed(activity, new RuntimeException("Activity not found: " + activity.activityName()));
            return;
        }

        // 2. 컨텍스트 생성
        // TODO: ActivityExecution 데이터를 기반으로 정교한 WorkflowContext를 생성하는 ContextFactory 연동 필요
        // 현재는 수동으로 생성 (향후 리팩토링 대상)
        DefaultWorkflowContext context = new DefaultWorkflowContext(
            activity.instanceId(),
            "UNKNOWN", // WorkflowName은 Instance 테이블 조회가 필요할 수 있음
            activity.activityName(),
            activity.retryCnt() + 1,
            activity.inputData()
        );
        context.attributes().put("executionId", activity.id());

        try {
            log.info("Executing activity: {} (Execution ID: {})", activity.activityName(), activity.id());
            
            // 3. 리플렉션을 통한 메서드 호출
            // 입력 파라미터가 있을 경우 JSON 역직렬화 및 타입 매칭 로직 보완
            Object[] args = resolveArguments(metadata, activity.inputData());
            
            Object result = metadata.method().invoke(metadata.bean(), args);
            
            // 4. 성공 시 결과 업데이트 및 다음 단계 처리
            taskExecutor.complete(context, result);
            log.info("Activity completed: {} (Execution ID: {})", activity.activityName(), activity.id());
            
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException) ? e.getCause() : e;
            log.error("Failed to execute activity: " + activity.id(), cause);
            
            // 5. 실패 시 재시도 정책 적용
            int attempt = context.attempt();
            int maxRetry = metadata.annotation().retryCount();
            long baseBackoff = metadata.annotation().backoffSeconds();
            
            if (retryPolicy.isExceeded(attempt, maxRetry)) {
                log.error("Max retry count exceeded for activity: {}. Mark as FAILED.", activity.id());
                taskExecutor.fail(context, cause, null); // 더 이상 재시도 없음
            } else {
                Duration nextBackoff = retryPolicy.calculateNextBackoff(attempt, baseBackoff);
                log.info("Scheduling retry #{} for activity: {} with delay: {}s", attempt, activity.id(), nextBackoff.getSeconds());
                taskExecutor.fail(context, cause, nextBackoff);
            }
        }
    }

    /**
     * 리플렉션 호출을 위한 파라미터 바인딩 로직.
     * JSON 입력 데이터를 메서드 파라미터 타입에 맞게 역직렬화합니다.
     */
    private Object[] resolveArguments(WorkflowRegistry.ActivityMetadata metadata, String inputData) {
        Class<?>[] parameterTypes = metadata.method().getParameterTypes();
        if (parameterTypes.length == 0) {
            return new Object[0];
        }
        
        // 현재는 첫 번째 파라미터에 입력을 주입하는 것을 기본으로 함
        Object arg;
        Class<?> firstParamType = parameterTypes[0];
        
        if (firstParamType == String.class) {
            arg = inputData;
        } else {
            // engine-core의 JsonUtil을 사용하여 역직렬화 (WorkflowWorker가 이미 JsonUtil을 간접적으로 사용하거나 주입받을 수 있음)
            // 현재 구조에서는 WorkflowWorker에 JsonUtil 주입이 누락되어 있으므로, 필요한 경우 추가 주입 필요
            // 여기서는 단순함을 위해 String이 아니면 null 처리하거나 예외를 던질 수 있음
            // TODO: WorkflowWorker에 JsonUtil 주입 및 정교한 역직렬화 구현
            arg = inputData; 
        }
        
        return new Object[]{arg};
    }

    private void updateActivityAsCompleted(ActivityExecution activity, Object output) {
        ActivityExecution completed = new ActivityExecution(
            activity.id(), activity.instanceId(), activity.activityName(),
            "COMPLETED", activity.inputData(), String.valueOf(output),
            null, null, activity.retryCnt(), null,
            activity.startDt(), LocalDateTime.now(),
            activity.useFl(), activity.viewFl(), activity.delFl(),
            activity.regDt(), activity.regId(), LocalDateTime.now(), "worker"
        );
        activityRepository.updateStatus(completed);
    }

    private void updateActivityAsFailed(ActivityExecution activity, Exception e) {
        // 실제로는 @Activity의 재시도 정책을 읽어 다음 재시도 시간을 계산해야 함
        int nextRetryCnt = activity.retryCnt() + 1;
        LocalDateTime nextRetryDt = LocalDateTime.now().plus(Duration.ofSeconds(30)); // 30초 후 재시도 예시

        ActivityExecution failed = new ActivityExecution(
            activity.id(), activity.instanceId(), activity.activityName(),
            "FAILED", activity.inputData(), null,
            e.getMessage(), stackTraceToString(e), nextRetryCnt, nextRetryDt,
            activity.startDt(), LocalDateTime.now(),
            activity.useFl(), activity.viewFl(), activity.delFl(),
            activity.regDt(), activity.regId(), LocalDateTime.now(), "worker"
        );
        activityRepository.updateStatus(failed);
    }

    private String stackTraceToString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}

