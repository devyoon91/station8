package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * WorkflowExecutor 인터페이스의 실구현체.
 */
@Service
public class JdbcWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowExecutor.class);

    private final JdbcTemplate jdbcTemplate;
    private final ActivityRepository activityRepository;
    private final JsonUtil jsonUtil;

    public JdbcWorkflowExecutor(JdbcTemplate jdbcTemplate, 
                                ActivityRepository activityRepository, 
                                JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.activityRepository = activityRepository;
        this.jsonUtil = jsonUtil;
    }

    @Override
    @Transactional
    public String startWorkflow(String workflowName, Object input) {
        String instanceId = UUID.randomUUID().toString();
        String inputJson = jsonUtil.toJson(input);

        log.info("Starting workflow: {} (Instance ID: {})", workflowName, instanceId);

        // U_WF_INSTANCE 기록 (Aspect에서도 기록되지만, 명시적 호출 대응)
        jdbcTemplate.update("""
            INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId, workflowName, inputJson);

        // 첫 번째 액티비티를 찾는 로직이 필요할 수 있으나, 
        // 현재 엔진은 개발자가 Workflow 로직 내에서 첫 Activity를 호출하거나
        // 외부에서 첫 Activity PENDING을 넣어주는 방식으로 동작함.
        
        return instanceId;
    }

    @Override
    @Transactional
    public void resumeWorkflow(String instanceId) {
        log.info("Resuming workflow instance: {}", instanceId);

        // 1. 인스턴스 상태를 RUNNING으로 복구 (FAILED인 경우 대비)
        jdbcTemplate.update("""
            UPDATE U_WF_INSTANCE 
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
}

