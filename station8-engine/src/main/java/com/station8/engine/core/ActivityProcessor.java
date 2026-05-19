package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.DlqEntry;
import com.station8.engine.entity.LineStation;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.DlqRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * #146 — 단일 활동 실행 책임을 담당하는 sub-service.
 *
 * <p>{@link LineWorker}는 폴링/디스패치만 담당하고, 가져온 {@link ActivityExecution}을
 * 본 sub-service에 위임해 실제 처리한다. 처리 흐름:</p>
 *
 * <ol>
 *   <li>레지스트리에서 활동 메타데이터 조회 — 누락 시 즉시 fail</li>
 *   <li>{@link LineContextFactory}로 {@link LineContext} + {@link RunOptions} 조립</li>
 *   <li>{@link ActivityArgumentResolver}로 메서드 인자 바인딩 (#108/#113/#134)</li>
 *   <li>리플렉션 invoke + {@link TaskExecutor#complete} 또는 {@link TaskExecutor#fail}</li>
 *   <li>DAG 모드면 {@link DagInterpreter#onNodeCompleted} 호출 (선행 완료 fan-out)</li>
 *   <li>실패 시 재시도 정책 / DLQ 적재 / {@link RunOptions#onFailure} 분기 (#134/#148)</li>
 * </ol>
 *
 * <p>본 클래스의 모든 메서드는 worker 스레드 풀에서 실행되며, 한 활동 처리 중 발생한
 * 예외는 모두 캐치되어 활동 단위 실패로 처리된다 — 다른 활동의 처리에 영향을 주지 않는다.</p>
 */
@Component
public class ActivityProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivityProcessor.class);

    /** 활동 메타데이터 누락 시 다음 재시도까지 대기 — 운영자 수동 등록 가능성 고려. */
    private static final Duration METADATA_MISSING_RETRY_DELAY = Duration.ofSeconds(30);

    private final ActivityRepository activityRepository;
    private final TaskExecutor taskExecutor;
    private final LineRegistry workflowRegistry;
    private final ExponentialBackoffRetryPolicy retryPolicy;
    private final DlqRepository dlqRepository;
    private final DlqNotifier dlqNotifier;
    private final JsonUtil jsonUtil;
    private final DagInterpreter dagInterpreter;
    private final ActivityArgumentResolver argumentResolver;
    private final LineDefinitionRepository definitionRepository;
    private final LineExecutor lineExecutor;
    private final LineContextFactory contextFactory;
    private final InputParamsEvaluator inputParamsEvaluator;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param activityRepository    활동 상태 업데이트 (메타 누락 시 FAILED 마킹)
     * @param taskExecutor          활동 결과 complete/fail 처리
     * @param workflowRegistry      활동 이름 → 메서드 메타데이터 lookup
     * @param retryPolicy           재시도 횟수/백오프 계산
     * @param dlqRepository         최종 실패 활동 DLQ 적재
     * @param dlqNotifier           DLQ 적재 시 웹훅 알림 발송
     * @param jsonUtil              station bindings JSON 파싱
     * @param dagInterpreter        DAG 모드에서 후행 활동 fan-out
     * @param argumentResolver      활동 메서드 파라미터 바인딩 (String / DataSource / LineContext)
     * @param definitionRepository  station 메타 조회 (datasourceBindings)
     * @param lineExecutor          onFailure=ABORT/PAUSE 시 인스턴스 전이
     * @param contextFactory        {@link LineContext} + {@link RunOptions} 조립
     * @param inputParamsEvaluator  M16 (#247) — inputData의 {@code {{ ... }}} 표현식 평가
     */
    public ActivityProcessor(ActivityRepository activityRepository,
                             TaskExecutor taskExecutor,
                             LineRegistry workflowRegistry,
                             ExponentialBackoffRetryPolicy retryPolicy,
                             DlqRepository dlqRepository,
                             DlqNotifier dlqNotifier,
                             JsonUtil jsonUtil,
                             DagInterpreter dagInterpreter,
                             ActivityArgumentResolver argumentResolver,
                             LineDefinitionRepository definitionRepository,
                             LineExecutor lineExecutor,
                             LineContextFactory contextFactory,
                             InputParamsEvaluator inputParamsEvaluator) {
        this.activityRepository = activityRepository;
        this.taskExecutor = taskExecutor;
        this.workflowRegistry = workflowRegistry;
        this.retryPolicy = retryPolicy;
        this.dlqRepository = dlqRepository;
        this.dlqNotifier = dlqNotifier;
        this.jsonUtil = jsonUtil;
        this.dagInterpreter = dagInterpreter;
        this.argumentResolver = argumentResolver;
        this.definitionRepository = definitionRepository;
        this.lineExecutor = lineExecutor;
        this.contextFactory = contextFactory;
        this.inputParamsEvaluator = inputParamsEvaluator;
    }

    /**
     * 단일 활동을 실행하고 결과를 업데이트한다. worker 스레드 풀에서 호출되며 본 메서드는
     * 예외를 위로 전파하지 않는다 (모두 활동 단위 fail 처리).
     *
     * @param activity 실행 대상 활동
     */
    public void process(ActivityExecution activity) {
        // 1. 레지스트리에서 활동 메타데이터 조회 — 누락은 운영 결함이므로 FAILED 마킹 후 종료
        LineRegistry.ActivityMetadata metadata = workflowRegistry.getActivity(activity.activityName());
        if (metadata == null) {
            log.error("No registered activity found for name: {}", activity.activityName());
            markActivityFailed(activity,
                    new RuntimeException("Activity not found: " + activity.activityName()));
            return;
        }

        // 2. 컨텍스트 + RunOptions 조립 — Factory가 instance 로드 + 옵션 파싱까지 담당
        LineContextFactory.Bundle bundle = contextFactory.create(activity);
        DefaultLineContext context = bundle.context();
        RunOptions options = bundle.options();

        try {
            log.info("Executing activity: {} (Execution ID: {})", activity.activityName(), activity.id());

            // 3. M16 (#247) — inputData의 {{ ... }} 표현식 평가. 실패는 활동 FAILED로 격하.
            //    표현식 없으면 inputData 그대로 (오버헤드 0).
            String evaluatedInput = inputParamsEvaluator.evaluate(activity.inputData(), context);

            // 4. 파라미터 바인딩은 ActivityArgumentResolver에 위임:
            //    - #108: String + DataSourceRegistry
            //    - #113: @BoundDataSource JdbcTemplate (station 바인딩 기반)
            //    - #134: LineContext (runtime params 접근용)
            ActivityArgumentResolver.Context resolveCtx = buildResolveContext(activity, context, evaluatedInput);
            Object[] args = argumentResolver.resolve(metadata.method(), resolveCtx);

            // 5. 리플렉션 invoke
            Object result = metadata.method().invoke(metadata.bean(), args);

            // 6. 성공 처리 + (DAG 모드) 후행 활성화
            taskExecutor.complete(context, result);
            log.info("Activity completed: {} (Execution ID: {})", activity.activityName(), activity.id());
            if (activity.nodeId() != null) {
                dagInterpreter.onNodeCompleted(activity.instanceId(), activity.nodeId());
            }
        } catch (Exception e) {
            // InvocationTargetException은 실제 활동 메서드의 예외를 감싸므로 unwrap
            Throwable cause = (e instanceof InvocationTargetException) ? e.getCause() : e;
            handleFailure(activity, context, options, metadata, cause);
        }
    }

    /**
     * 활동 실행 실패 처리 — 재시도 횟수 검사 후 재시도 스케줄 또는 최종 실패(DLQ + onFailure 분기).
     */
    private void handleFailure(ActivityExecution activity,
                               DefaultLineContext context,
                               RunOptions options,
                               LineRegistry.ActivityMetadata metadata,
                               Throwable cause) {
        log.error("Failed to execute activity: " + activity.id(), cause);

        int attempt = context.attempt();
        int maxRetry = metadata.annotation().retryCount();
        long baseBackoff = metadata.annotation().backoffSeconds();

        // NoRetryException — 재시도 무의미한 실패(HTTP 4xx, 입력 검증 등)는 즉시 final-fail.
        boolean skipRetry = cause instanceof NoRetryException
                || (cause != null && cause.getCause() instanceof NoRetryException);
        if (skipRetry || retryPolicy.isExceeded(attempt, maxRetry)) {
            if (skipRetry) {
                log.error("NoRetryException — skipping retry, mark as FAILED_FINAL: {}", activity.id());
            } else {
                log.error("Max retry count exceeded for activity: {}. Mark as FAILED_FINAL.", activity.id());
            }
            taskExecutor.fail(context, cause, null); // 더 이상 재시도 없음
            // DLQ 적재 — instance webhook override 우선 적용 (#134 D8)
            moveToDlq(activity, context, cause, maxRetry, options.notificationWebhookUrl());

            // #134/#148 — onFailure 정책별 분기
            switch (options.onFailure()) {
                case ABORT -> abortInstance(activity.instanceId());
                case PAUSE_ON_FAILURE -> pauseInstanceOnFailure(activity.instanceId());
                case CONTINUE -> { /* 다른 활동은 계속 진행 — 기본 동작 */ }
            }
        } else {
            Duration nextBackoff = retryPolicy.calculateNextBackoff(attempt, baseBackoff);
            log.info("Scheduling retry #{} for activity: {} with delay: {}s",
                    attempt, activity.id(), nextBackoff.getSeconds());
            taskExecutor.fail(context, cause, nextBackoff);
        }
    }

    /**
     * 활동 메서드 호출 시 ArgumentResolver에 넘길 컨텍스트 구성. DAG 모드(nodeId 보유)면
     * station을 조회해 datasourceBindings를 파싱. 레거시(선형) 모드 또는 station 미발견이면
     * 빈 bindings — 모든 {@code @BoundDataSource}는 primary fallback.
     *
     * @param activity       실행 대상 활동
     * @param lineContext    활동에 주입할 {@link LineContext} (runtime params 접근용 — #134 D7)
     * @param evaluatedInput {@link InputParamsEvaluator}가 풀어낸 input — 활동 메서드의 String 파라미터에 전달
     * @return resolver에 넘길 입력 컨텍스트
     */
    private ActivityArgumentResolver.Context buildResolveContext(ActivityExecution activity,
                                                                 LineContext lineContext,
                                                                 String evaluatedInput) {
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
        return new ActivityArgumentResolver.Context(evaluatedInput, bindings, lineContext);
    }

    /**
     * 활동 메타데이터 누락 시 FAILED로 마킹 (재시도 카운트는 증가하지만, 다음 폴링에서도 같은 사유로 실패).
     * 운영자가 누락된 activity를 등록하거나 dlqRepository를 통해 별도 처리 가능.
     */
    private void markActivityFailed(ActivityExecution activity, Exception e) {
        int nextRetryCnt = activity.retryCnt() + 1;
        LocalDateTime nextRetryDt = LocalDateTime.now().plus(METADATA_MISSING_RETRY_DELAY);

        ActivityExecution failed = new ActivityExecution(
                activity.id(), activity.instanceId(), activity.nodeId(), activity.activityName(),
                "FAILED", activity.inputData(), null,
                e.getMessage(), formatStackTrace(e), nextRetryCnt, nextRetryDt,
                activity.startDt(), LocalDateTime.now(),
                activity.delFl(),
                activity.regDt(), activity.regId(), LocalDateTime.now(), "worker"
        );
        activityRepository.updateStatus(failed);
    }

    /**
     * 최대 재시도 초과 시 DLQ에 적재하고 웹훅 알림을 발송한다.
     *
     * @param webhookOverride 인스턴스 {@link RunOptions#notificationWebhookUrl} (null이면 전역 webhook — #134 D8)
     */
    private void moveToDlq(ActivityExecution activity, DefaultLineContext context, Throwable cause,
                           int maxRetry, String webhookOverride) {
        try {
            DlqEntry entry = new DlqEntry(
                    null, // ID는 Repository에서 UUID 자동 생성
                    activity.instanceId(),
                    activity.id(),
                    context.workflowName(),
                    activity.activityName(),
                    "NEW",
                    cause.getMessage(),
                    formatStackTraceWithHeader(cause),
                    context.attempt(),
                    maxRetry,
                    LocalDateTime.now(),
                    null, null, null, null, null
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

    /**
     * onFailure=ABORT 처리. 다른 활동이 먼저 트리거해 이미 종료된 경우는 idempotent하게 무시.
     */
    private void abortInstance(String instanceId) {
        log.warn("onFailure=ABORT — terminating instance: {}", instanceId);
        try {
            lineExecutor.terminateLine(instanceId);
        } catch (IllegalStateException terminateEx) {
            // 다른 활동이 이미 abort 트리거 → instance가 RUNNING이 아닐 수 있음 (idempotent)
            log.info("terminateLine 무시 — 이미 종료된 인스턴스: {} ({})",
                    instanceId, terminateEx.getMessage());
        } catch (Exception terminateEx) {
            log.error("terminateLine 실패 — instance: {}", instanceId, terminateEx);
        }
    }

    /**
     * #148 — onFailure=PAUSE_ON_FAILURE 처리. 활동 최종 실패 시 인스턴스를 PAUSED로 마킹.
     * 운영자가 timeline에서 Unpause + 활동 retry(#139) 또는 Terminate 결정.
     * 다른 활동이 이미 트리거하거나 인스턴스가 이미 종료된 경우 idempotent.
     */
    private void pauseInstanceOnFailure(String instanceId) {
        log.warn("onFailure=PAUSE_ON_FAILURE — pausing instance: {}", instanceId);
        try {
            lineExecutor.pauseLine(instanceId);
        } catch (IllegalStateException pauseEx) {
            // 인스턴스가 RUNNING이 아니어서 pause 불가 — 다른 활동이 먼저 trigger했거나 이미 PAUSED/TERMINATED
            log.info("pauseLine 무시 — RUNNING 아님: {} ({})",
                    instanceId, pauseEx.getMessage());
        } catch (Exception pauseEx) {
            log.error("pauseLine 실패 — instance: {}", instanceId, pauseEx);
        }
    }

    /** DLQ 적재용 스택트레이스 — 예외 toString() 헤더 + tab-prefixed frames. */
    private static String formatStackTraceWithHeader(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append("\n");
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append("\tat ").append(element).append("\n");
        }
        return sb.toString();
    }

    /** 활동 FAILED 컬럼용 스택트레이스 — frame 한 줄씩. */
    private static String formatStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
