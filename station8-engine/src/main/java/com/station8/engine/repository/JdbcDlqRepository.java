package com.station8.engine.repository;

import com.station8.engine.entity.DlqEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DlqRepository의 JDBC 기반 구현체.
 * H_LINE_DLQ 테이블에 대한 CRUD를 수행합니다.
 */
@Repository
public class JdbcDlqRepository implements DlqRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDlqRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(DlqEntry entry) {
        String id = (entry.id() != null) ? entry.id() : UUID.randomUUID().toString();
        String sql = """
            INSERT INTO H_LINE_DLQ (
                ID, INSTANCE_ID, EXECUTION_ID, WORKFLOW_NAME, ACTIVITY_NAME,
                DLQ_STATUS_ST, ERROR_MESSAGE, STACK_TRACE,
                RETRY_CNT, MAX_RETRY_CNT, FAILED_AT_DT,
                USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'engine')
            """;
        jdbcTemplate.update(sql,
            id,
            entry.instanceId(),
            entry.executionId(),
            entry.workflowName(),
            entry.activityName(),
            entry.dlqStatusSt(),
            entry.errorMessage(),
            entry.stackTrace(),
            entry.retryCnt(),
            entry.maxRetryCnt(),
            entry.failedAtDt()
        );
    }

    @Override
    public List<DlqEntry> findAll() {
        String sql = "SELECT * FROM H_LINE_DLQ ORDER BY REG_DT DESC";
        return jdbcTemplate.query(sql, new DlqEntryRowMapper());
    }

    @Override
    public DlqEntry findById(String id) {
        String sql = "SELECT * FROM H_LINE_DLQ WHERE ID = ?";
        return jdbcTemplate.queryForObject(sql, new DlqEntryRowMapper(), id);
    }

    @Override
    public void updateStatus(String id, String newStatus) {
        String sql = "UPDATE H_LINE_DLQ SET DLQ_STATUS_ST = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = 'engine' WHERE ID = ?";
        jdbcTemplate.update(sql, newStatus, id);
    }

    private static class DlqEntryRowMapper implements RowMapper<DlqEntry> {
        @Override
        public DlqEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DlqEntry(
                rs.getString("ID"),
                rs.getString("INSTANCE_ID"),
                rs.getString("EXECUTION_ID"),
                rs.getString("WORKFLOW_NAME"),
                rs.getString("ACTIVITY_NAME"),
                rs.getString("DLQ_STATUS_ST"),
                rs.getString("ERROR_MESSAGE"),
                rs.getString("STACK_TRACE"),
                rs.getInt("RETRY_CNT"),
                rs.getObject("MAX_RETRY_CNT") != null ? rs.getInt("MAX_RETRY_CNT") : null,
                rs.getTimestamp("FAILED_AT_DT") != null ? rs.getTimestamp("FAILED_AT_DT").toLocalDateTime() : null,
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT") != null ? rs.getTimestamp("REG_DT").toLocalDateTime() : null,
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }
}
