package com.station8.engine.core;

import com.station8.engine.entity.LineSchedule;
import com.station8.engine.repository.LineScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cron 스케줄 폴러: 만료된 ``U_LINE_SCHEDULE`` 행을 SKIP LOCKED로 가져와
 * 라인 인스턴스를 시작하고 ``NEXT_RUN_DT``를 다음 cron 시각으로 갱신한다.
 *
 * <p>인스턴스 시작 로직은 {@link TriggerLauncher}로 위임 — webhook 등 다른 trigger와 같은
 * 공통 시퀀스 (정의 lookup → 동시성 평가 → instance INSERT → DAG 시작). 본 클래스는 cron 특화
 * 부분 (스케줄 polling + nextRun 갱신)만 책임.</p>
 *
 * <p>분산 환경 안전성: ``findDueWithLock``의 SKIP LOCKED로 두 워커가 동일 스케줄을 동시 트리거하지 않는다.</p>
 *
 * <p>미스파이어 정책: 폴링이 늦어 ``NEXT_RUN_DT``가 과거가 되어도 단 1회만 트리거한다.
 * ``markRun`` 시 새 ``NEXT_RUN_DT``는 ``now`` 기준 cron.next(now)로 계산되므로 누락은 1회로 한정된다.</p>
 */
@Component
public class LineScheduler {

    private static final Logger log = LoggerFactory.getLogger(LineScheduler.class);
    private static final int DEFAULT_BATCH_LIMIT = 20;

    private final LineScheduleRepository scheduleRepository;
    private final TriggerLauncher triggerLauncher;

    public LineScheduler(LineScheduleRepository scheduleRepository,
                         TriggerLauncher triggerLauncher) {
        this.scheduleRepository = scheduleRepository;
        this.triggerLauncher = triggerLauncher;
    }

    /**
     * 주기적 폴링 진입점. 기본 30초 간격이며 ``workflow.scheduler.interval-ms``로 조정 가능.
     */
    @Scheduled(fixedDelayString = "${workflow.scheduler.interval-ms:30000}")
    public void pollSchedules() {
        pollOnce(DEFAULT_BATCH_LIMIT);
    }

    /**
     * 단일 폴링 처리 — 테스트에서 직접 호출 가능. 트랜잭션 단위로 묶여 SKIP LOCKED 잠금이 유효하다.
     *
     * @return 트리거된 인스턴스 ID 목록 (skip / failure 제외)
     */
    @Transactional
    public List<String> pollOnce(int limit) {
        List<LineSchedule> due = scheduleRepository.findDueWithLock(limit);
        if (due.isEmpty()) {
            log.trace("No due schedules");
            return List.of();
        }
        log.info("Triggering {} due schedule(s)", due.size());
        return due.stream().map(this::triggerOne).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 한 스케줄 트리거. {@link TriggerLauncher#launch}로 위임 후 cron 특화 후처리 (nextRun 갱신).
     * 실패 시 nextRun을 1분 뒤로 — 스피닝 방지.
     */
    private String triggerOne(LineSchedule s) {
        try {
            TriggerLauncher.LaunchResult result = triggerLauncher.launch(
                    s.definitionId(), s.inputData(), "Scheduler:" + s.id());
            advanceNextRun(s);
            if (result.started()) {
                log.info("[Scheduler] triggered scheduleId={}, definitionId={}, instanceId={}",
                        s.id(), s.definitionId(), result.instanceId());
                return result.instanceId();
            }
            log.warn("[Scheduler] SKIP — scheduleId={}, policy={}, conflicting={}",
                    s.id(), result.skipReasonPolicy(), result.conflictingInstanceId());
            return null;
        } catch (Exception e) {
            // 실패 시 다음 폴링에서 재시도 가능하도록 nextRun을 1분 뒤로 이동(스피닝 방지)
            log.error("[Scheduler] 트리거 실패 scheduleId={}", s.id(), e);
            try {
                LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(1);
                scheduleRepository.markRun(s.id(), nextRetry, LocalDateTime.now());
            } catch (Exception ex) {
                log.error("[Scheduler] markRun 실패 scheduleId={}", s.id(), ex);
            }
            return null;
        }
    }

    /** cron 표현식 기반 다음 실행 시각 계산 + markRun. */
    private void advanceNextRun(LineSchedule s) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = nextFromCron(s.cronExpr(), now);
        scheduleRepository.markRun(s.id(), nextRun, now);
    }

    /**
     * Spring CronExpression을 사용해 ``base`` 이후의 다음 발화 시각을 계산한다.
     * 표현식이 잘못되었거나 다음 시각이 없으면 1시간 뒤로 fallback (운영 가시성을 위해 멈추지 않음).
     */
    public static LocalDateTime nextFromCron(String cronExpr, LocalDateTime base) {
        try {
            CronExpression ce = CronExpression.parse(cronExpr);
            LocalDateTime next = ce.next(base);
            return next != null ? next : base.plusHours(1);
        } catch (IllegalArgumentException e) {
            // 잘못된 cron 표현식 — 1시간 뒤로 미루고 운영자가 수정하도록
            return base.plusHours(1);
        }
    }
}
