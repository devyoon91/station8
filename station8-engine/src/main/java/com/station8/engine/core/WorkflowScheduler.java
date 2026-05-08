package com.station8.engine.core;

import com.station8.engine.entity.WorkflowSchedule;
import com.station8.engine.repository.WorkflowScheduleRepository;
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
 * Cron 스케줄 폴러: 만료된 ``U_WF_SCHEDULE`` 행을 SKIP LOCKED로 가져와
 * 워크플로우 인스턴스를 시작하고 ``NEXT_RUN_DT``를 다음 cron 시각으로 갱신한다.
 *
 * <p>분산 환경 안전성: ``findDueWithLock``의 SKIP LOCKED로 두 워커가 동일 스케줄을 동시 트리거하지 않는다.</p>
 *
 * <p>미스파이어 정책: 폴링이 늦어 ``NEXT_RUN_DT``가 과거가 되어도 단 1회만 트리거한다.
 * ``markRun`` 시 새 ``NEXT_RUN_DT``는 ``now`` 기준 cron.next(now)로 계산되므로 누락은 1회로 한정된다.</p>
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);
    private static final int DEFAULT_BATCH_LIMIT = 20;

    private final WorkflowScheduleRepository scheduleRepository;
    private final DagInterpreter dagInterpreter;
    private final JdbcTemplate jdbcTemplate;

    public WorkflowScheduler(WorkflowScheduleRepository scheduleRepository,
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
        List<WorkflowSchedule> due = scheduleRepository.findDueWithLock(limit);
        if (due.isEmpty()) {
            log.trace("No due schedules");
            return List.of();
        }
        log.info("Triggering {} due schedule(s)", due.size());

        return due.stream().map(this::triggerOne).toList();
    }

    private String triggerOne(WorkflowSchedule s) {
        String instanceId = UUID.randomUUID().toString();
        try {
            // 1) 인스턴스 INSERT
            String workflowName = jdbcTemplate.queryForObject(
                    "SELECT DEFINITION_NM FROM U_WF_DEFINITION WHERE ID = ?",
                    String.class, s.definitionId());
            jdbcTemplate.update("""
                    INSERT INTO U_WF_INSTANCE
                      (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
                    VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, instanceId, workflowName, s.inputData());

            // 2) DAG 시작 (검증 + 시작 노드 PENDING + 후행 WAITING_DEPENDENCIES)
            dagInterpreter.startInstance(s.definitionId(), instanceId, s.inputData());

            // 3) 다음 실행 시각 계산 + markRun
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
