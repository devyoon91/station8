package com.station8.engine.core;

import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.InstanceQueryFilter;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * #138 — SLA 위반 폴러.
 *
 * <p>주기적으로 RUNNING 인스턴스를 조회해 SLA 임계치를 초과한 것에 대해 알림 발송 + 옵션에 따라 auto-terminate.</p>
 *
 * <h3>SLA 결정 우선순위</h3>
 * <ol>
 *   <li>인스턴스 RUN_OPTIONS의 {@code slaSeconds} / {@code slaAction} (override) — #134</li>
 *   <li>정의({@code U_LINE_DEFINITION})의 {@code SLA_SECONDS} / {@code SLA_ACTION} (default)</li>
 *   <li>둘 다 없으면 SLA 비활성 — skip</li>
 * </ol>
 *
 * <h3>액션</h3>
 * <ul>
 *   <li>{@link SlaAction#ALERT_ONLY} — webhook 알림만 발송</li>
 *   <li>{@link SlaAction#AUTO_TERMINATE} — 알림 + {@link LineExecutor#terminateLineWithReason} 호출</li>
 * </ul>
 *
 * <p>폴링 주기는 {@code engine.sla.polling-interval-ms} (default 60000ms = 1분).
 * 한 사이클에 처리할 인스턴스 수는 {@code MAX_BATCH_SIZE} (default 20) — DB 부담 제한.</p>
 */
@Component
public class SlaPoller {

    private static final Logger log = LoggerFactory.getLogger(SlaPoller.class);
    private static final int MAX_BATCH_SIZE = 20;

    private final ActivityRepository activityRepository;
    private final LineDefinitionRepository definitionRepository;
    private final SlaNotifier slaNotifier;
    private final LineExecutor lineExecutor;
    private final JsonUtil jsonUtil;

    public SlaPoller(ActivityRepository activityRepository,
                     LineDefinitionRepository definitionRepository,
                     SlaNotifier slaNotifier,
                     LineExecutor lineExecutor,
                     JsonUtil jsonUtil) {
        this.activityRepository = activityRepository;
        this.definitionRepository = definitionRepository;
        this.slaNotifier = slaNotifier;
        this.lineExecutor = lineExecutor;
        this.jsonUtil = jsonUtil;
    }

    @Scheduled(fixedDelayString = "${engine.sla.polling-interval-ms:60000}")
    public void pollSlaViolations() {
        log.trace("Polling SLA violations...");
        InstanceQueryFilter filter = new InstanceQueryFilter(
                null, List.of("RUNNING"), null, null, null, "START_DT", "ASC");
        List<LineInstance> running = activityRepository.findInstancesPage(filter, 0, MAX_BATCH_SIZE);

        if (running.isEmpty()) return;
        log.debug("[SLA] Checking {} RUNNING instances", running.size());

        for (LineInstance inst : running) {
            try {
                checkOne(inst);
            } catch (Exception ex) {
                log.error("[SLA] poller — instance 처리 중 예외, 다음 인스턴스로 계속: {}",
                        inst.id(), ex);
            }
        }
    }

    private void checkOne(LineInstance inst) {
        if (inst.startDt() == null) return;

        // 1. 인스턴스 override 우선
        RunOptions opts = RunOptions.parse(inst.runOptions(), jsonUtil);
        Long thresholdSeconds = opts.slaSeconds();
        SlaAction action = opts.slaAction();

        // 2. 정의의 default로 fallback
        if (thresholdSeconds == null || action == null) {
            LineDefinition def = definitionRepository.findActiveDefinitionByName(inst.workflowName());
            if (def != null) {
                if (thresholdSeconds == null) thresholdSeconds = def.slaSeconds();
                if (action == null && def.slaAction() != null) action = SlaAction.parse(def.slaAction());
            }
        }

        if (thresholdSeconds == null) return;  // SLA 미설정 — skip
        if (action == null) action = SlaAction.ALERT_ONLY;  // 안전한 default

        // 3. 경과 시간 계산
        long elapsed = Duration.between(inst.startDt(), LocalDateTime.now()).getSeconds();
        if (elapsed < thresholdSeconds) return;  // 아직 위반 아님

        // 4. SLA 위반 — 알림 발송 + 액션 적용
        SlaViolation violation = new SlaViolation(
                inst.id(), inst.workflowName(), inst.startDt(), elapsed, thresholdSeconds, action);
        log.warn("[SLA] VIOLATION instance={}, workflow={}, elapsed={}s, threshold={}s, action={}",
                inst.id(), inst.workflowName(), elapsed, thresholdSeconds, action);
        slaNotifier.notify(violation, opts.notificationWebhookUrl());

        if (action == SlaAction.AUTO_TERMINATE) {
            String reason = String.format(
                    "SLA violation: elapsed %ds exceeds threshold %ds (auto-terminate)",
                    elapsed, thresholdSeconds);
            lineExecutor.terminateLineWithReason(inst.id(), reason);
        }
    }
}
