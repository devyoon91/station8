package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * #146 — DB에서 실행 대기 중인 활동을 폴링하고 worker 스레드 풀로 디스패치하는 워커.
 *
 * <p>{@code @Scheduled}로 주기적 폴링만 담당하며, 실제 활동 실행은 {@link ActivityProcessor}에
 * 위임한다. {@link PipelineGate}는 dispatch 직전 호출되어 PIPELINE_* 정책 차단을 결정한다.</p>
 *
 * <h3>책임 분리 (#146)</h3>
 * <ul>
 *   <li>{@link ActivityProcessor} — 단일 활동 실행 (메타 lookup → 인자 바인딩 → invoke → DLQ/abort/pause)</li>
 *   <li>{@link LineContextFactory} — {@link LineContext} + {@link RunOptions} 조립</li>
 *   <li>본 {@link LineWorker} — 폴링 + 게이트 검사 + executor 위임만</li>
 * </ul>
 */
@Component
public class LineWorker {

    private static final Logger log = LoggerFactory.getLogger(LineWorker.class);

    /** 한 폴링 사이클에서 가져올 활동 수 상한 — DB 잠금 시간 + worker 처리 시간 트레이드오프. */
    private static final int POLL_BATCH_LIMIT = 10;

    private final ActivityRepository activityRepository;
    private final ThreadPoolTaskExecutor workflowTaskExecutor;
    private final PipelineGate pipelineGate;
    private final ActivityProcessor activityProcessor;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param activityRepository    PENDING 활동 조회 + 게이트 차단 시 PENDING 복구
     * @param workflowTaskExecutor  활동 실행 worker 스레드 풀
     * @param pipelineGate          dispatch 전 PIPELINE_* 정책 차단 평가
     * @param activityProcessor     실제 활동 실행 위임 대상
     */
    public LineWorker(ActivityRepository activityRepository,
                      ThreadPoolTaskExecutor workflowTaskExecutor,
                      PipelineGate pipelineGate,
                      ActivityProcessor activityProcessor) {
        this.activityRepository = activityRepository;
        this.workflowTaskExecutor = workflowTaskExecutor;
        this.pipelineGate = pipelineGate;
        this.activityProcessor = activityProcessor;
    }

    /**
     * 주기적으로 DB를 폴링하여 처리 가능한 활동을 가져온 뒤, worker 스레드 풀로 디스패치한다.
     * 폴링 주기는 {@code workflow.polling.interval-ms} property (기본 1000ms).
     */
    @Scheduled(fixedDelayString = "${workflow.polling.interval-ms:1000}")
    public void pollActivities() {
        log.trace("Polling pending activities...");
        List<ActivityExecution> pendingActivities =
                activityRepository.findPendingActivitiesWithLock(POLL_BATCH_LIMIT);

        for (ActivityExecution activity : pendingActivities) {
            // #164 — Pipeline 게이트 검사. 차단되면 PENDING 복구 + NEXT_RETRY_DT 지연.
            if (activity.nodeId() != null && !canDispatchUnderPipeline(activity)) {
                LocalDateTime retryAt = LocalDateTime.now().plus(PipelineGate.GATE_BACKOFF);
                activityRepository.revertGateBlocked(activity.id(), retryAt);
                log.debug("Pipeline gate 차단 — activity={}, instance={}, retry@{}",
                        activity.id(), activity.instanceId(), retryAt);
                continue;
            }

            log.info("Dispatching activity: {} (Instance ID: {})",
                    activity.activityName(), activity.instanceId());
            workflowTaskExecutor.execute(() -> activityProcessor.process(activity));
        }
    }

    /**
     * #164 — Pipeline 게이트 검사. workflowName 해석 후 게이트에 위임.
     * 예외 발생 시 안전 통과(true) — 게이트 오류로 활동이 멈추지 않도록.
     *
     * @param activity dispatch 후보 활동
     * @return true = dispatch OK, false = 게이트로 차단
     */
    private boolean canDispatchUnderPipeline(ActivityExecution activity) {
        try {
            LineInstance instance = activityRepository.findInstanceById(activity.instanceId());
            if (instance == null || instance.workflowName() == null) {
                return true;
            }
            return pipelineGate.canDispatch(
                    activity.instanceId(), activity.nodeId(), instance.workflowName());
        } catch (Exception ex) {
            log.warn("Pipeline gate 평가 실패 — activity={}, fallback=allow ({}: {})",
                    activity.id(), ex.getClass().getSimpleName(), ex.getMessage());
            return true;
        }
    }
}
