package com.station8.engine.core;

import com.station8.engine.entity.LineSchedule;
import com.station8.engine.repository.LineScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cron 스케줄 폴러: 만료된 ``U_LINE_SCHEDULE`` 행을 SKIP LOCKED로 가져와
 * 라인 인스턴스를 시작하고 ``NEXT_RUN_DT``를 다음 cron 시각으로 갱신한다.
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
    private final DagInterpreter dagInterpreter;
    private final JdbcTemplate jdbcTemplate;

    public LineScheduler(LineScheduleRepository scheduleRepository,
                             DagInterpreter dagInterpreter,
                             JdbcTemplate jdbcTemplate) {
        this.scheduleRepository = scheduleRepository;
        this.dagInterpreter = dagInterpreter;
        this.jdbcTemplate = jdbcTemplate;
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
     * @return 트리거된 인스턴스 ID 목록
     */
    @Transactional
    public List<String> pollOnce(int limit) {
        List<LineSchedule> due = scheduleRepository.findDueWithLock(limit);
        if (due.isEmpty()) {
            log.trace("No due schedules");
            return List.of();
        }
        log.info("Triggering {} due schedule(s)", due.size());

        return due.stream().map(this::triggerOne).toList();
    }

    private String triggerOne(LineSchedule s) {
        String instanceId = UUID.randomUUID().toString();
        try {
            // 1) 정의 메타 조회 (workflowName + concurrency 정책)
            java.util.List<java.util.Map<String, Object>> defMeta = jdbcTemplate.queryForList(
                    "SELECT DEFINITION_NM, CONCURRENCY_POLICY FROM U_LINE_DEFINITION WHERE ID = ?",
                    s.definitionId());
            if (defMeta.isEmpty()) {
                throw new IllegalStateException("정의를 찾을 수 없습니다: " + s.definitionId());
            }
            String workflowName = (String) defMeta.get(0).get("DEFINITION_NM");
            String policy = (String) defMeta.get(0).get("CONCURRENCY_POLICY");

            // 2) #141, #177 — Concurrency strategy 평가 (cron 적체 방지)
            ConcurrencyStrategy strategy = ConcurrencyStrategy.parse(policy);
            ConcurrencyStrategy.StartContext startCtx = new ConcurrencyStrategy.StartContext(
                    workflowName,
                    () -> firstActiveInstanceIdForLock(workflowName)
            );
            ConcurrencyStrategy.StartResult startResult = strategy.evaluateOnStart(startCtx);
            if (!startResult.allowed()) {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime nextRun = nextFromCron(s.cronExpr(), now);
                scheduleRepository.markRun(s.id(), nextRun, now);
                log.warn("[Scheduler] SKIP — scheduleId={}, definitionId={}, policy={}, conflicting={}, nextRun={}",
                        s.id(), s.definitionId(), strategy.policyName(),
                        startResult.conflictingInstanceId(), nextRun);
                return null;  // skipped
            }

            // 3) 인스턴스 INSERT
            jdbcTemplate.update("""
                    INSERT INTO U_LINE_INSTANCE
                      (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
                    VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, instanceId, workflowName, s.inputData());

            // 4) DAG 시작 (검증 + 시작 역 PENDING + 후행 WAITING_DEPENDENCIES)
            dagInterpreter.startInstance(s.definitionId(), instanceId, s.inputData());

            // 5) 다음 실행 시각 계산 + markRun
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextRun = nextFromCron(s.cronExpr(), now);
            scheduleRepository.markRun(s.id(), nextRun, now);

            log.info("[Scheduler] triggered scheduleId={}, definitionId={}, instanceId={}, nextRun={}",
                    s.id(), s.definitionId(), instanceId, nextRun);
            return instanceId;
        } catch (Exception e) {
            // 실패 시 다음 폴링에서 재시도 가능하도록 nextRun을 1분 뒤로 이동(스피닝 방지)
            log.error("[Scheduler] 트리거 실패 scheduleId={}, instanceId={}", s.id(), instanceId, e);
            try {
                LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(1);
                scheduleRepository.markRun(s.id(), nextRetry, LocalDateTime.now());
            } catch (Exception ex) {
                log.error("[Scheduler] markRun 실패 scheduleId={}", s.id(), ex);
            }
            return null;
        }
    }

    /**
     * #141/#177 — 같은 workflow의 RUNNING/PAUSED 활성 인스턴스 1건 ID (락 보유). 없으면 null.
     * triggerOne 트랜잭션 안에서 호출 — FOR UPDATE 락이 트랜잭션 끝까지 유지되어 동시 호출 race 방지.
     */
    private String firstActiveInstanceIdForLock(String workflowName) {
        java.util.List<String> active = jdbcTemplate.queryForList(
                "SELECT ID FROM U_LINE_INSTANCE "
                        + "WHERE WORKFLOW_NAME = ? AND STATUS_ST IN ('RUNNING', 'PAUSED') "
                        + "AND DEL_FL = 'N' FOR UPDATE",
                String.class, workflowName);
        return active.isEmpty() ? null : active.get(0);
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
