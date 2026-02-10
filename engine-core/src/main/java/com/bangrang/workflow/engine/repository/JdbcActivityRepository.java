package com.bangrang.workflow.engine.repository;

import com.bangrang.workflow.engine.dialect.DbDialect;
import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.entity.WorkflowInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JdbcTemplate 기반의 ActivityRepository 구현체.
 * Oracle과 MariaDB의 SKIP LOCKED 기능을 활용하여 분산 락 없이 작업 큐를 구현합니다.
 */
@Repository
public class JdbcActivityRepository implements ActivityRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DbDialect dbDialect;

    public JdbcActivityRepository(JdbcTemplate jdbcTemplate, DbDialect dbDialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbDialect = dbDialect;
    }

    /**
     * PENDING 상태이고 실행 시간이 된 작업을 SKIP LOCKED로 조회하고 즉시 RUNNING으로 업데이트합니다.
     */
    @Override
    @Transactional
    public List<ActivityExecution> findPendingActivitiesWithLock(int limit) {
        // 1. SKIP LOCKED를 사용하여 잠금된 레코드 조회
        // Oracle & MariaDB 10.6+ 공통 문법
        String selectSql = String.format("""
            SELECT * FROM H_WF_ACTIVITY_EXECUTION
            WHERE STATUS_ST = 'PENDING'
              AND (NEXT_RETRY_DT IS NULL OR NEXT_RETRY_DT <= %s)
              AND USE_FL = 'Y'
              AND DEL_FL = 'N'
            ORDER BY REG_DT ASC
            FOR UPDATE SKIP LOCKED
            %s
            """, dbDialect.currentTimestamp(), dbDialect.limit(limit));
        
        List<ActivityExecution> activities = jdbcTemplate.query(selectSql, new ActivityExecutionRowMapper());
        
        if (activities.isEmpty()) {
            return activities;
        }

        // 2. 조회된 작업들의 상태를 RUNNING으로 즉시 업데이트 (다른 워커가 가로채지 못하도록)
        for (ActivityExecution activity : activities) {
            jdbcTemplate.update(
                String.format("UPDATE H_WF_ACTIVITY_EXECUTION SET STATUS_ST = 'RUNNING', START_DT = %s, EDIT_DT = %s WHERE ID = ?",
                    dbDialect.currentTimestamp(), dbDialect.currentTimestamp()),
                activity.id()
            );
        }

        return activities;
    }

    @Override
    @Transactional
    public void updateStatus(ActivityExecution activity) {
        String sql = String.format("""
            UPDATE H_WF_ACTIVITY_EXECUTION
            SET STATUS_ST = ?,
                OUTPUT_DATA = ?,
                ERROR_MESSAGE = ?,
                STACK_TRACE = ?,
                RETRY_CNT = ?,
                NEXT_RETRY_DT = ?,
                END_DT = ?,
                EDIT_DT = %s
            WHERE ID = ?
            """, dbDialect.currentTimestamp());
        
        jdbcTemplate.update(sql,
            activity.statusSt(),
            activity.outputData(),
            activity.errorMessage(),
            activity.stackTrace(),
            activity.retryCnt(),
            activity.nextRetryDt(),
            activity.endDt(),
            activity.id()
        );
    }

    @Override
    @Transactional
    public void createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt) {
        String id = UUID.randomUUID().toString();
        String sql = String.format("""
            INSERT INTO H_WF_ACTIVITY_EXECUTION (
                ID, INSTANCE_ID, ACTIVITY_NAME, STATUS_ST, INPUT_DATA,
                RETRY_CNT, NEXT_RETRY_DT, USE_FL, VIEW_FL, DEL_FL, REG_DT
            ) VALUES (
                ?, ?, ?, 'PENDING', ?,
                0, ?, 'Y', 'Y', 'N', %s
            )
            """, dbDialect.currentTimestamp());
        jdbcTemplate.update(sql,
            id,
            instanceId,
            activityName,
            inputData,
            nextRetryDt
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowInstance> findAllInstances() {
        String sql = "SELECT * FROM U_WF_INSTANCE ORDER BY REG_DT DESC";
        return jdbcTemplate.query(sql, new WorkflowInstanceRowMapper());
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowInstance findInstanceById(String instanceId) {
        String sql = "SELECT * FROM U_WF_INSTANCE WHERE ID = ?";
        return jdbcTemplate.queryForObject(sql, new WorkflowInstanceRowMapper(), instanceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityExecution> findActivitiesByInstanceId(String instanceId) {
        String sql = "SELECT * FROM H_WF_ACTIVITY_EXECUTION WHERE INSTANCE_ID = ? ORDER BY REG_DT ASC";
        return jdbcTemplate.query(sql, new ActivityExecutionRowMapper(), instanceId);
    }

    @Override
    @Transactional
    public void resetToPending(String executionId) {
        String sql = String.format("""
            UPDATE H_WF_ACTIVITY_EXECUTION
            SET STATUS_ST = 'PENDING',
                NEXT_RETRY_DT = NULL,
                START_DT = NULL,
                END_DT = NULL,
                EDIT_DT = %s
            WHERE ID = ?
            """, dbDialect.currentTimestamp());
        jdbcTemplate.update(sql, executionId);
    }

    private static class WorkflowInstanceRowMapper implements RowMapper<WorkflowInstance> {
        @Override
        public WorkflowInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WorkflowInstance(
                rs.getString("ID"),
                rs.getString("WORKFLOW_NAME"),
                rs.getString("STATUS_ST"),
                rs.getString("INPUT_DATA"),
                rs.getString("OUTPUT_DATA"),
                rs.getString("STATE_DATA"),
                rs.getTimestamp("START_DT") != null ? rs.getTimestamp("START_DT").toLocalDateTime() : null,
                rs.getTimestamp("END_DT") != null ? rs.getTimestamp("END_DT").toLocalDateTime() : null,
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT").toLocalDateTime(),
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }

    private static class ActivityExecutionRowMapper implements RowMapper<ActivityExecution> {
        @Override
        public ActivityExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ActivityExecution(
                rs.getString("ID"),
                rs.getString("INSTANCE_ID"),
                rs.getString("ACTIVITY_NAME"),
                rs.getString("STATUS_ST"),
                rs.getString("INPUT_DATA"),
                rs.getString("OUTPUT_DATA"),
                rs.getString("ERROR_MESSAGE"),
                rs.getString("STACK_TRACE"),
                rs.getInt("RETRY_CNT"),
                rs.getTimestamp("NEXT_RETRY_DT") != null ? rs.getTimestamp("NEXT_RETRY_DT").toLocalDateTime() : null,
                rs.getTimestamp("START_DT") != null ? rs.getTimestamp("START_DT").toLocalDateTime() : null,
                rs.getTimestamp("END_DT") != null ? rs.getTimestamp("END_DT").toLocalDateTime() : null,
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT").toLocalDateTime(),
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }
}

