package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.DlqEntry;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineStation;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.DlqRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.springframework.dao.EmptyResultDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DB에서 실행 대기 중인 작업을 가져와 스레드 풀에서 실행하는 워커 클래스.
 * Spring의 @Scheduled를 이용해 주기적으로 폴링하며, ThreadPoolTaskExecutor를 통해 동시성을 제어합니다.
 */
@Component
public class LineWorker {

    private static final Logger log = LoggerFactory.getLogger(LineWorker.class);

    private final ActivityRepository activityRepository;
    private final TaskExecutor taskExecutor;
    private final ThreadPoolTaskExecutor workflowTaskExecutor;
    private final LineRegistry workflowRegistry;
    private final ExponentialBackoffRetryPolicy retryPolicy;
    private final DlqRepository dlqRepository;
    private final DlqNotifier dlqNotifier;
    private final JsonUtil jsonUtil;
    private final DagInterpreter dagInterpreter;
    private final ActivityArgumentResolver argumentResolver;
    private final LineDefinitionRepository definitionRepository;
    private final LineExecutor lineExecutor;
    private final PipelineGate pipelineGate;

    public LineWorker(ActivityRepository activityRepository,
                          TaskExecutor taskExecutor,
                          ThreadPoolTaskExecutor workflowTaskExecutor,
                          LineRegistry workflowRegistry,
                          ExponentialBackoffRetryPolicy retryPolicy,
                          DlqRepository dlqRepository,
                          DlqNotifier dlqNotifier,
                          JsonUtil jsonUtil,
                          DagInterpreter dagInterpreter,
                          ActivityArgumentResolver argumentResolver,
                          LineDefinitionRepository definitionRepository,
                          LineExecutor lineExecutor,
                          PipelineGate pipelineGate) {
        this.activityRepository = activityRepository;
        this.taskExecutor = taskExecutor;
        this.workflowTaskExecutor = workflowTaskExecutor;
        this.workflowRegistry = workflowRegistry;
        this.retryPolicy = retryPolicy;
        this.dlqRepository = dlqRepository;
        this.dlqNotifier = dlqNotifier;
        this.jsonUtil = jsonUtil;
        this.dagInterpreter = dagInterpreter;
        this.argumentResolver = argumentResolver;
        this.definitionRepository = definitionRepository;
        this.lineExecutor = lineExecutor;
        this.pipelineGate = pipelineGate;
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
            // #164 — Pipeline 게이트 검사. 차단되면 PENDING 복구 + NEXT_RETRY_DT 지연.
            if (activity.nodeId() != null && !canDispatchUnderPipeline(activity)) {
                LocalDateTime retryAt = LocalDateTime.now().plus(PipelineGate.GATE_BACKOFF);
                activityRepository.revertGateBlocked(activity.id(), retryAt);
                log.debug("[#164] Pipeline gate 차단 — activity={}, instance={}, retry@{}",
                        activity.id(), activity.instanceId(), retryAt);
                continue;
            }

            log.info("Dispatching activity: {} (Instance ID: {})", activity.activityName(), activity.instanceId());

            // 별도의 스레드 풀에서 비동기 실행
            workflowTaskExecutor.execute(() -> processActivity(activity));
        }
    }

    /**
     * #164 — Pipeline 게이트 검사. workflowName 해석 후 게이트에 위임.
     * 예외 발생 시 안전 통과(true) — 게이트 오류로 활동이 멈추지 않도록.
     */
    private boolean canDispatchUnderPipeline(ActivityExecution activity) {
        try {
            LineInstance instance = activityRepository.findInstanceById(activity.instanceId());
            if (instance == null || instance.workflowName() == null) return true;
            return pipelineGate.canDispatch(activity.instanceId(), activity.nodeId(), instance.workflowName());
        } catch (Exception ex) {
            log.warn("[#164] Pipeline gate 평가 실패 — activity={}, fallback=allow ({}: {})",
                    activity.id(), ex.getClass().getSimpleName(), ex.getMessage());
            return true;
        }
    }

    /**
     * 개별 액티비티를 실행하고 결과를 업데이트하는 실제 로직.
     */
    private void processActivity(ActivityExecution activity) {
        // 1. 레지스트리에서 액티비티 메타데이터 조회
        LineRegistry.ActivityMetadata metadata = workflowRegistry.getActivity(activity.activityName());
        if (metadata == null) {
            log.error("No registered activity found for name: {}", activity.activityName());
            updateActivityAsFailed(activity, new RuntimeException("Activity not found: " + activity.activityName()));
            return;
        }

        // 2. 인스턴스 메타 + RunOptions 로드 (#134) — 실패해도 default로 fallback
        LineInstance instance = loadInstanceSafely(activity.instanceId());
        RunOptions options = parseRunOptionsSafely(instance);

        // 3. 컨텍스트 생성 — workflowName은 instance에서, runtime params는 options에서 (#134 D7)
        String workflowName = (instance != null && instance.workflowName() != null)
                ? instance.workflowName()
                : "UNKNOWN";
        DefaultLineContext context = new DefaultLineContext(
            activity.instanceId(),
            workflowName,
            activity.activityName(),
            activity.retryCnt() + 1,
            activity.inputData(),
            null, // previousOutput
            jsonUtil
        );
        context.attributes().put("executionId", activity.id());
        context.setRuntimeParams(options.runtimeParams());

        try {
            log.info("Executing activity: {} (Execution ID: {})", activity.activityName(), activity.id());

            // 4. 리플렉션을 통한 메서드 호출
            // 파라미터 바인딩은 ActivityArgumentResolver에 위임:
            //  - #108: String + DataSourceRegistry
            //  - #113: @BoundDataSource JdbcTemplate (station 바인딩 기반)
            //  - #134: LineContext (runtime params 접근용)
            ActivityArgumentResolver.Context resolveCtx = buildResolveContext(activity, context);
            Object[] args = argumentResolver.resolve(metadata.method(), resolveCtx);

            Object result = metadata.method().invoke(metadata.bean(), args);

            // 5. 성공 시 결과 업데이트 및 다음 단계 처리
            taskExecutor.complete(context, result);
            log.info("Activity completed: {} (Execution ID: {})", activity.activityName(), activity.id());

            // 5-1. DAG 모드(NODE_ID 보유)면 인터프리터에 후행 역 활성화 위임
            if (activity.nodeId() != null) {
                dagInterpreter.onNodeCompleted(activity.instanceId(), activity.nodeId());
            }

        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException) ? e.getCause() : e;
            log.error("Failed to execute activity: " + activity.id(), cause);

            // 6. 실패 시 재시도 정책 적용
            int attempt = context.attempt();
            int maxRetry = metadata.annotation().retryCount();
            long baseBackoff = metadata.annotation().backoffSeconds();

            if (retryPolicy.isExceeded(attempt, maxRetry)) {
                log.error("Max retry count exceeded for activity: {}. Mark as FAILED_FINAL.", activity.id());
                taskExecutor.fail(context, cause, null); // 더 이상 재시도 없음
                // FAILED_FINAL 상태로 인스턴스 업데이트 및 DLQ 적재 — instance webhook override 적용 (#134 D8)
                moveToDlq(activity, context, cause, maxRetry, options.notificationWebhookUrl());

                // #134/#148 — onFailure 정책별 분기
                switch (options.onFailure()) {
                    case ABORT -> abortInstance(activity.instanceId());
                    case PAUSE_ON_FAILURE -> pauseInstanceOnFailure(activity.instanceId());
                    case CONTINUE -> { /* 다른 활동은 계속 진행 — 기본 동작 */ }
                }
            } else {
                Duration nextBackoff = retryPolicy.calculateNextBackoff(attempt, baseBackoff);
                log.info("Scheduling retry #{} for activity: {} with delay: {}s", attempt, activity.id(), nextBackoff.getSeconds());
                taskExecutor.fail(context, cause, nextBackoff);
            }
        }
    }

    /**
     * 인스턴스 메타 안전 조회 — 누락/조회 실패는 null로 fallback.
     */
    private LineInstance loadInstanceSafely(String instanceId) {
        try {
            return activityRepository.findInstanceById(instanceId);
        } catch (EmptyResultDataAccessException ex) {
            log.warn("Instance not found — id={}, fallback to defaults", instanceId);
            return null;
        } catch (Exception ex) {
            log.warn("Instance 조회 실패 — id={}, fallback to defaults ({}: {})",
                    instanceId, ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    /**
     * {@link LineInstance#runOptions()} CLOB JSON을 안전하게 파싱한다 — 파싱 실패 시 default로 fallback.
     */
    private RunOptions parseRunOptionsSafely(LineInstance instance) {
        if (instance == null) return RunOptions.defaults();
        try {
            return RunOptions.parse(instance.runOptions(), jsonUtil);
        } catch (Exception ex) {
            log.warn("RunOptions 파싱 실패 — instanceId={}, fallback to defaults ({}: {})",
                    instance.id(), ex.getClass().getSimpleName(), ex.getMessage());
            return RunOptions.defaults();
        }
    }

    /**
     * #134 D1=γ — onFailure=ABORT 처리. 다른 액티비티가 먼저 트리거해 이미 종료된 경우는 idempotent하게 무시.
     */
    private void abortInstance(String instanceId) {
        log.warn("[#134] onFailure=ABORT — terminating instance: {}", instanceId);
        try {
            lineExecutor.terminateLine(instanceId);
        } catch (IllegalStateException terminateEx) {
            // 다른 활동이 이미 abort 트리거 → instance가 RUNNING이 아닐 수 있음 (idempotent)
            log.info("[#134] terminateLine 무시 — 이미 종료된 인스턴스: {} ({})",
                    instanceId, terminateEx.getMessage());
        } catch (Exception terminateEx) {
            log.error("[#134] terminateLine 실패 — instance: {}", instanceId, terminateEx);
        }
    }

    /**
     * #148 — onFailure=PAUSE_ON_FAILURE 처리. 활동 최종 실패 시 인스턴스를 PAUSED로 마킹.
     * 운영자가 timeline에서 Unpause + 활동 retry(#139) 또는 Terminate 결정.
     * 다른 활동이 이미 트리거하거나 인스턴스가 이미 종료된 경우 idempotent.
     */
    private void pauseInstanceOnFailure(String instanceId) {
        log.warn("[#148] onFailure=PAUSE_ON_FAILURE — pausing instance: {}", instanceId);
        try {
            lineExecutor.pauseLine(instanceId);
        } catch (IllegalStateException pauseEx) {
            // 인스턴스가 RUNNING이 아니어서 pause 불가 — 다른 활동이 먼저 trigger했거나 이미 PAUSED/TERMINATED
            log.info("[#148] pauseLine 무시 — RUNNING 아님: {} ({})",
                    instanceId, pauseEx.getMessage());
        } catch (Exception pauseEx) {
            log.error("[#148] pauseLine 실패 — instance: {}", instanceId, pauseEx);
        }
    }

    /**
     * 액티비티 호출 시 ArgumentResolver에 넘길 컨텍스트를 구성한다.
     * DAG 모드(NODE_ID 보유)이면 station을 조회해 datasourceBindings를 파싱.
     * 레거시(선형) 모드 또는 station 미발견이면 빈 bindings — 모든 @BoundDataSource는 primary fallback.
     *
     * @param lineContext 액티비티에 주입할 LineContext (runtime params 접근용 — #134 D7)
     */
    private ActivityArgumentResolver.Context buildResolveContext(ActivityExecution activity,
                                                                 LineContext lineContext) {
        Map<String, String> bindings = Collections.emptyMap();
        if (activity.nodeId() != null) {
            try {
                LineStation station = definitionRepository.findStationById(activity.nodeId());
                if (station != null && station.datasourceBindings() != null
                        && !station.datasourceBindings().isBlank()) {
                    bindings = jsonUtil.fromJsonToStringMap(station.datasourceBindings());
                }
            } catch (Exception ex) {
                log.warn("Station 조회 실패 — nodeId={}, fallback to empty bindings ({}: {})",
                        activity.nodeId(), ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        return new ActivityArgumentResolver.Context(activity.inputData(), bindings, lineContext);
    }

    private void updateActivityAsCompleted(ActivityExecution activity, Object output) {
        ActivityExecution completed = new ActivityExecution(
            activity.id(), activity.instanceId(), activity.nodeId(), activity.activityName(),
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
            activity.id(), activity.instanceId(), activity.nodeId(), activity.activityName(),
            "FAILED", activity.inputData(), null,
            e.getMessage(), stackTraceToString(e), nextRetryCnt, nextRetryDt,
            activity.startDt(), LocalDateTime.now(),
            activity.useFl(), activity.viewFl(), activity.delFl(),
            activity.regDt(), activity.regId(), LocalDateTime.now(), "worker"
        );
        activityRepository.updateStatus(failed);
    }

    /**
     * 최대 재시도 초과 시 DLQ에 적재하고 웹훅 알림을 발송합니다.
     *
     * @param webhookOverride 인스턴스 RunOptions의 notificationWebhookUrl (null이면 전역 webhook 사용 — #134 D8)
     */
    private void moveToDlq(ActivityExecution activity, DefaultLineContext context, Throwable cause,
                           int maxRetry, String webhookOverride) {
        try {
            String stackTrace = buildStackTraceString(cause);
            DlqEntry entry = new DlqEntry(
                null, // ID는 Repository에서 UUID 자동 생성
                activity.instanceId(),
                activity.id(),
                context.workflowName(),
                activity.activityName(),
                "NEW",
                cause.getMessage(),
                stackTrace,
                context.attempt(),
                maxRetry,
                LocalDateTime.now(),
                null, null, null, null, null, null, null
            );
            dlqRepository.insert(entry);
            log.info("[DLQ] 적재 완료. Activity={}, Instance={}", activity.activityName(), activity.instanceId());

            // 웹훅 알림 발송 — 인스턴스 override 우선, 없으면 전역 (#134 D8)
            // (비동기 아님 — 실패해도 DLQ 적재는 보장됨)
            dlqNotifier.notify(entry, webhookOverride);
        } catch (Exception dlqEx) {
            log.error("[DLQ] 적재 중 오류 발생. Activity={}", activity.id(), dlqEx);
        }
    }

    private String buildStackTraceString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append("\n");
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append("\tat ").append(element).append("\n");
        }
        return sb.toString();
    }

    private String stackTraceToString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}

