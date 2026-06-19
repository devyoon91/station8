package com.station8.engine.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M20 (#310) — 라인 인스턴스를 시작하는 공통 진입점. Cron / Webhook / 향후 다른 trigger 모두
 * 같은 launcher를 거친다.
 *
 * <p>{@link LineScheduler}에서 추출 — concurrency 정책 평가 + instance INSERT +
 * {@link DagInterpreter#startInstance} 호출까지의 공통 시퀀스를 한 곳에. trigger type별 부가
 * 로직(예: cron의 nextRun 갱신)은 호출자가 책임.</p>
 *
 * <h3>왜 interface 안 만들고 클래스로</h3>
 * 두 번째 trigger 타입(webhook) 도입 시점이라 추상화가 premature. 공통 로직을 캡슐화한 클래스
 * 하나로 충분하다. 3번째 trigger(Kafka 등)가 생기면 그때 interface 발현.
 */
@Component
public class TriggerLauncher {

    private static final Logger log = LoggerFactory.getLogger(TriggerLauncher.class);

    private final DagInterpreter dagInterpreter;
    private final JdbcTemplate jdbcTemplate;

    public TriggerLauncher(DagInterpreter dagInterpreter, JdbcTemplate jdbcTemplate) {
        this.dagInterpreter = dagInterpreter;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 라인 인스턴스 시작 시퀀스.
     *
     * <ol>
     *   <li>U_LINE_DEFINITION에서 workflowName + concurrencyPolicy 조회</li>
     *   <li>{@link ConcurrencyStrategy}로 시작 가능 여부 평가 (락 보유 후)</li>
     *   <li>차단 시 {@link LaunchResult#skipped} 반환 — 호출자가 retry 정책 결정</li>
     *   <li>허용 시 U_LINE_INSTANCE INSERT + {@link DagInterpreter#startInstance}</li>
     * </ol>
     *
     * <p>본 메서드는 {@code @Transactional} — concurrency 평가의 FOR UPDATE 락이 INSERT까지
     * 유지되어 동시 호출 race가 차단된다.</p>
     *
     * @param definitionId  라인 정의 ID
     * @param inputData     인스턴스 input (JSON string)
     * @param triggerSource 로그용 식별자 (예: {@code "cron:<scheduleId>"}, {@code "webhook:<key>"})
     * @return 시작 성공 시 {@code instanceId} 포함, 차단 시 사유
     */
    @Transactional
    public LaunchResult launch(String definitionId, String inputData, String triggerSource) {
        List<Map<String, Object>> defMeta = jdbcTemplate.queryForList(
                "SELECT DEFINITION_NM, CONCURRENCY_POLICY FROM U_LINE_DEFINITION WHERE ID = ?",
                definitionId);
        if (defMeta.isEmpty()) {
            throw new IllegalStateException("정의를 찾을 수 없습니다: " + definitionId);
        }
        String workflowName = (String) defMeta.get(0).get("DEFINITION_NM");
        String policy = (String) defMeta.get(0).get("CONCURRENCY_POLICY");

        ConcurrencyStrategy strategy = ConcurrencyStrategy.parse(policy);
        ConcurrencyStrategy.StartContext startCtx = new ConcurrencyStrategy.StartContext(
                workflowName,
                () -> firstActiveInstanceIdForLock(workflowName));
        ConcurrencyStrategy.StartResult startResult = strategy.evaluateOnStart(startCtx);

        if (!startResult.allowed()) {
            log.warn("[{}] launch SKIP — definitionId={}, policy={}, conflicting={}",
                    triggerSource, definitionId, strategy.policyName(),
                    startResult.conflictingInstanceId());
            return LaunchResult.skipped(workflowName, strategy.policyName(),
                    startResult.conflictingInstanceId());
        }

        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE
                  (ID, WORKFLOW_NAME, DEFINITION_ID, STATUS_ST, INPUT_DATA, DEL_FL, START_DT, REG_DT)
                VALUES (?, ?, ?, 'RUNNING', ?, 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId, workflowName, definitionId, inputData);  // #364 — 런타임 정의 스코프 조회용

        dagInterpreter.startInstance(definitionId, instanceId, inputData);

        log.info("[{}] launched — definitionId={}, instanceId={}",
                triggerSource, definitionId, instanceId);
        return LaunchResult.started(instanceId, workflowName);
    }

    /**
     * 같은 workflow의 RUNNING/PAUSED 활성 인스턴스 1건 ID (락 보유). 없으면 null.
     * {@link #launch} 트랜잭션 안에서 호출 — FOR UPDATE 락이 트랜잭션 끝까지 유지되어
     * 동시 호출 race 방지.
     */
    private String firstActiveInstanceIdForLock(String workflowName) {
        List<String> active = jdbcTemplate.queryForList(
                "SELECT ID FROM U_LINE_INSTANCE "
                        + "WHERE WORKFLOW_NAME = ? AND STATUS_ST IN ('RUNNING', 'PAUSED') "
                        + "AND DEL_FL = 'N' FOR UPDATE",
                String.class, workflowName);
        return active.isEmpty() ? null : active.get(0);
    }

    /**
     * launch 결과. 호출자가 trigger type별 후속 처리(스케줄 advance, webhook 응답)에 사용.
     *
     * @param started                 시작 여부
     * @param instanceId              시작됐으면 instance ID, 차단이면 null
     * @param workflowName            정의 이름 (둘 다 채워짐 — 로그용)
     * @param skipReasonPolicy        차단 시 정책 이름 (예: {@code SKIP_IF_RUNNING})
     * @param conflictingInstanceId   차단 사유가 된 활성 instance ID
     */
    public record LaunchResult(
            boolean started,
            String instanceId,
            String workflowName,
            String skipReasonPolicy,
            String conflictingInstanceId
    ) {
        public static LaunchResult started(String instanceId, String workflowName) {
            return new LaunchResult(true, instanceId, workflowName, null, null);
        }

        public static LaunchResult skipped(String workflowName, String policyName, String conflictingId) {
            return new LaunchResult(false, null, workflowName, policyName, conflictingId);
        }
    }
}
