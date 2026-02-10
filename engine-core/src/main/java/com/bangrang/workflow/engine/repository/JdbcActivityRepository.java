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
 * JdbcTemplate 湲곕컲??ActivityRepository 援ы쁽泥?
 * Oracle怨?MariaDB??SKIP LOCKED 湲곕뒫???쒖슜?섏뿬 遺꾩궛 ???놁씠 ?묒뾽 ?먮? 援ы쁽?⑸땲??
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
     * PENDING ?곹깭?닿퀬 ?ㅽ뻾 ?쒓컙?????묒뾽??SKIP LOCKED濡?議고쉶?섍퀬 利됱떆 RUNNING?쇰줈 ?낅뜲?댄듃?⑸땲??
     */
    @Override
    @Transactional
    public List<ActivityExecution> findPendingActivitiesWithLock(int limit) {
        // 1. SKIP LOCKED瑜??ъ슜?섏뿬 ?좉툑???덉퐫??議고쉶
        // Oracle & MariaDB 10.6+ 怨듯넻 臾몃쾿
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

        // 2. 議고쉶???묒뾽?ㅼ쓽 ?곹깭瑜?RUNNING?쇰줈 利됱떆 ?낅뜲?댄듃 (?ㅻⅨ ?뚯빱媛 媛濡쒖콈吏 紐삵븯?꾨줉)
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

