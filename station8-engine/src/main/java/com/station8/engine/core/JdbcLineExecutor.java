package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * LineExecutor 인터페이스의 실구현체.
 */
@Service
public class JdbcLineExecutor implements LineExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcLineExecutor.class);

    private final JdbcTemplate jdbcTemplate;
    private final ActivityRepository activityRepository;
    private final JsonUtil jsonUtil;
    private final DagInterpreter dagInterpreter;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcLineExecutor(JdbcTemplate jdbcTemplate,
                            ActivityRepository activityRepository,
                            JsonUtil jsonUtil,
                            @Lazy DagInterpreter dagInterpreter) {
        this.jdbcTemplate = jdbcTemplate;
        this.activityRepository = activityRepository;
        this.jsonUtil = jsonUtil;
        this.dagInterpreter = dagInterpreter;
    }

    /** 후방 호환 — 3-arg 생성자 (DagInterpreter 없이 — Pause/Unpause/Retry 비활성). 테스트용. */
    public JdbcLineExecutor(JdbcTemplate jdbcTemplate,
                            ActivityRepository activityRepository,
                            JsonUtil jsonUtil) {
        this(jdbcTemplate, activityRepository, jsonUtil, null);
    }

    @Override
    @Transactional
    public String startLine(String workflowName, Object input) {
        String instanceId = UUID.randomUUID().toString();
        String inputJson = jsonUtil.toJson(input);

        log.info("Starting workflow: {} (Instance ID: {})", workflowName, instanceId);

        // U_LINE_INSTANCE 기록 (Aspect에서도 기록되지만, 명시적 호출 대응)
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, 'RUNNING', ?, 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId, workflowName, inputJson);

        // 첫 번째 액티비티를 찾는 로직이 필요할 수 있으나, 
        // 현재 엔진은 개발자가 Line 로직 내에서 첫 Activity를 호출하거나
        // 외부에서 첫 Activity PENDING을 넣어주는 방식으로 동작함.
        
        return instanceId;
    }

    @Override
    @Transactional
    public void resumeLine(String instanceId) {
        log.info("Resuming workflow instance: {}", instanceId);

        // 1. 인스턴스 상태를 RUNNING으로 복구 (FAILED인 경우 대비)
        jdbcTemplate.update("""
            UPDATE U_LINE_INSTANCE 
            SET STATUS_ST = 'RUNNING', EDIT_DT = CURRENT_TIMESTAMP 
            WHERE ID = ?
            """, instanceId);

        // 2. FAILED 상태이거나 중단된 PENDING이면서 NEXT_RETRY_DT가 먼 미래인 활동을 찾아 PENDING으로 복구
        List<ActivityExecution> activities = activityRepository.findActivitiesByInstanceId(instanceId);
        
        boolean resumed = false;
        for (ActivityExecution activity : activities) {
            if ("FAILED".equals(activity.statusSt())) {
                log.info("Reseting failed activity to PENDING: {} (ID: {})", activity.activityName(), activity.id());
                activityRepository.resetToPending(activity.id());
                resumed = true;
                // 한 번에 하나만 재개하거나 전체를 재개할 수 있으나, 여기서는 FAILED된 모든 것을 재개 대상으로 함.
            }
        }

        if (!resumed) {
            log.warn("No failed activities found to resume for instance: {}", instanceId);
        }
    }

    @Override
    @Transactional
    public void terminateLine(String instanceId) {
        // 1. 인스턴스 존재 + 상태 검증 (RUNNING만 종료 가능)
        LineInstance instance;
        try {
            instance = activityRepository.findInstanceById(instanceId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("인스턴스를 찾을 수 없습니다: " + instanceId);
        }
        if (instance == null) {
            throw new IllegalArgumentException("인스턴스를 찾을 수 없습니다: " + instanceId);
        }
        if (!"RUNNING".equals(instance.statusSt())) {
            throw new IllegalStateException(
                    "인스턴스가 RUNNING 상태가 아니라 종료할 수 없습니다 — 현재: " + instance.statusSt());
        }

        // 2. 인스턴스 → TERMINATED
        jdbcTemplate.update("""
            UPDATE U_LINE_INSTANCE
            SET STATUS_ST = 'TERMINATED', END_DT = CURRENT_TIMESTAMP,
                EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = 'terminate'
            WHERE ID = ?
            """, instanceId);

        // 3. 시작 안 한 액티비티 일괄 TERMINATED — RUNNING은 워커 자연 완료에 맡김(인스턴스가
        //    이미 TERMINATED라서 후행 fan-out은 DagInterpreter가 차단)
        int affected = activityRepository.bulkUpdateNotStartedStatuses(instanceId, "TERMINATED");
        log.info("Terminated workflow instance: {} ({} pending/waiting activities marked TERMINATED)",
                instanceId, affected);
    }

    @Override
    @Transactional
    public void terminateLineWithReason(String instanceId, String reason) {
        // #138 — terminateLine과 동일하지만 OUTPUT_DATA에 사유 기록 + RUNNING이 아니면 idempotent skip
        LineInstance instance;
        try {
            instance = activityRepository.findInstanceById(instanceId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            log.warn("terminateLineWithReason — 인스턴스 없음 (idempotent skip): {}", instanceId);
            return;
        }
        if (instance == null || !"RUNNING".equals(instance.statusSt())) {
            log.info("terminateLineWithReason 무시 — 이미 종료됨: {} (status={})",
                    instanceId, instance == null ? "null" : instance.statusSt());
            return;
        }

        String reasonJson = jsonUtil.toJson(java.util.Map.of("failureReason", reason == null ? "" : reason));
        jdbcTemplate.update("""
            UPDATE U_LINE_INSTANCE
            SET STATUS_ST = 'TERMINATED', OUTPUT_DATA = ?, END_DT = CURRENT_TIMESTAMP,
                EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = 'sla'
            WHERE ID = ?
            """, reasonJson, instanceId);

        int affected = activityRepository.bulkUpdateNotStartedStatuses(instanceId, "TERMINATED");
        log.warn("Instance auto-terminated: {} ({} pending/waiting marked TERMINATED) — reason: {}",
                instanceId, affected, reason);
    }

    @Override
    @Transactional
    public void failLine(String instanceId, String reason) {
        // 1. 인스턴스 상태 — RUNNING이 아니면 idempotent하게 무시 (이미 다른 경로로 종료됐을 수 있음)
        LineInstance instance;
        try {
            instance = activityRepository.findInstanceById(instanceId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            log.warn("failLine — 인스턴스 없음 (idempotent skip): {}", instanceId);
            return;
        }
        if (instance == null || !"RUNNING".equals(instance.statusSt())) {
            log.info("failLine 무시 — 이미 종료됨: {} (status={})",
                    instanceId, instance == null ? "null" : instance.statusSt());
            return;
        }

        // 2. 인스턴스 → FAILED + 사유 기록
        String reasonJson = jsonUtil.toJson(java.util.Map.of("failureReason", reason == null ? "" : reason));
        jdbcTemplate.update("""
            UPDATE U_LINE_INSTANCE
            SET STATUS_ST = 'FAILED', OUTPUT_DATA = ?, END_DT = CURRENT_TIMESTAMP,
                EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = 'engine'
            WHERE ID = ?
            """, reasonJson, instanceId);

        // 3. 시작 안 한 액티비티 정리 — terminateLine과 동일 패턴
        int affected = activityRepository.bulkUpdateNotStartedStatuses(instanceId, "TERMINATED");
        log.warn("Instance failed from condition: {} ({} pending/waiting marked TERMINATED) — reason: {}",
                instanceId, affected, reason);
    }

    @Override
    @Transactional
    public void pauseLine(String instanceId) {
        // #139 — RUNNING → PAUSED. 활동 상태는 그대로 유지 (Resume 시 자연 복구).
        LineInstance instance = loadInstance(instanceId);
        if (!"RUNNING".equals(instance.statusSt())) {
            throw new IllegalStateException(
                    "인스턴스가 RUNNING 상태가 아니라 일시 정지할 수 없습니다 — 현재: " + instance.statusSt());
        }
        jdbcTemplate.update("""
            UPDATE U_LINE_INSTANCE
            SET STATUS_ST = 'PAUSED', EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = 'pause'
            WHERE ID = ? AND STATUS_ST = 'RUNNING'
            """, instanceId);
        log.info("Paused instance: {} (PENDING/WAITING 활동은 그대로 — 워커 폴링이 인스턴스 상태로 차단)",
                instanceId);
    }

    @Override
    @Transactional
    public void unpauseLine(String instanceId) {
        // #139 — PAUSED → RUNNING. 일시정지 동안 RUNNING이 끝났을 가능성 → 모든 COMPLETED 활동에 대해
        // fan-out 재평가해 원래 promote됐어야 할 후행을 활성화한다.
        LineInstance instance = loadInstance(instanceId);
        if (!"PAUSED".equals(instance.statusSt())) {
            throw new IllegalStateException(
                    "인스턴스가 PAUSED 상태가 아니라 재개할 수 없습니다 — 현재: " + instance.statusSt());
        }
        jdbcTemplate.update("""
            UPDATE U_LINE_INSTANCE
            SET STATUS_ST = 'RUNNING', EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = 'unpause'
            WHERE ID = ? AND STATUS_ST = 'PAUSED'
            """, instanceId);

        // Pause 동안 RUNNING이 완료된 노드의 fan-out이 차단됐을 수 있음 → 재평가
        if (dagInterpreter != null) {
            List<ActivityExecution> activities = activityRepository.findActivitiesByInstanceId(instanceId);
            int reEvaluated = 0;
            for (ActivityExecution a : activities) {
                if ("COMPLETED".equals(a.statusSt()) && a.nodeId() != null) {
                    dagInterpreter.onNodeCompleted(instanceId, a.nodeId());
                    reEvaluated++;
                }
            }
            log.info("Unpaused instance: {} (fan-out 재평가 {}건 of COMPLETED nodes)",
                    instanceId, reEvaluated);
        } else {
            log.info("Unpaused instance: {} (DagInterpreter 미주입 — fan-out 재평가 skip)",
                    instanceId);
        }
    }

    @Override
    @Transactional
    public void retryActivity(String activityExecutionId) {
        // #139 — 단일 FAILED 활동만 PENDING으로 reset. 인스턴스가 RUNNING이고 활동이 FAILED일 때만 허용.
        ActivityExecution exec = activityRepository.findById(activityExecutionId);
        if (exec == null) {
            throw new IllegalArgumentException("활동 실행 기록을 찾을 수 없습니다: " + activityExecutionId);
        }
        if (!"FAILED".equals(exec.statusSt())) {
            throw new IllegalStateException(
                    "FAILED 상태가 아닌 활동은 retry할 수 없습니다 — 현재: " + exec.statusSt());
        }
        LineInstance instance = loadInstance(exec.instanceId());
        if (!"RUNNING".equals(instance.statusSt())) {
            throw new IllegalStateException(
                    "인스턴스가 RUNNING 상태가 아니어서 활동 retry할 수 없습니다 — 현재: " + instance.statusSt()
                    + " (Pause된 상태면 먼저 Unpause)");
        }
        activityRepository.resetToPending(activityExecutionId);
        log.info("Reset single activity to PENDING: id={}, name={}, instance={}",
                exec.id(), exec.activityName(), exec.instanceId());
    }

    private LineInstance loadInstance(String instanceId) {
        try {
            LineInstance instance = activityRepository.findInstanceById(instanceId);
            if (instance == null) {
                throw new IllegalArgumentException("인스턴스를 찾을 수 없습니다: " + instanceId);
            }
            return instance;
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("인스턴스를 찾을 수 없습니다: " + instanceId);
        }
    }
}

