package com.station8.engine.repository;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.DlqEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DlqRepository의 JDBC 기반 구현체.
 * H_LINE_DLQ 테이블에 대한 CRUD를 수행합니다.
 */
@Repository
public class JdbcDlqRepository implements DlqRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DbDialect dbDialect;

    public JdbcDlqRepository(JdbcTemplate jdbcTemplate, DbDialect dbDialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbDialect = dbDialect;
    }

    @Override
    public void insert(DlqEntry entry) {
        String id = (entry.id() != null) ? entry.id() : UUID.randomUUID().toString();
        String sql = """
            INSERT INTO H_LINE_DLQ (
                ID, INSTANCE_ID, EXECUTION_ID, WORKFLOW_NAME, ACTIVITY_NAME,
                DLQ_STATUS_ST, ERROR_MESSAGE, STACK_TRACE,
                RETRY_CNT, MAX_RETRY_CNT, FAILED_AT_DT,
                DEL_FL, REG_DT, REG_ID
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', CURRENT_TIMESTAMP, 'engine')
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
    public List<DlqEntry> findPage(DlqQueryFilter filter, int offset, int limit) {
        DlqFilter f = new DlqFilter(filter);
        String sql = "SELECT * FROM H_LINE_DLQ " + f.where()
                + " ORDER BY " + filter.sortBy() + " " + filter.sortDir()
                + " " + dbDialect.offsetLimit(offset, limit);
        return jdbcTemplate.query(sql, new DlqEntryRowMapper(), f.args().toArray());
    }

    @Override
    public long count(DlqQueryFilter filter) {
        DlqFilter f = new DlqFilter(filter);
        String sql = "SELECT COUNT(*) FROM H_LINE_DLQ " + f.where();
        Long n = jdbcTemplate.queryForObject(sql, Long.class, f.args().toArray());
        return n == null ? 0L : n;
    }

    /**
     * DLQ 필터를 동적 WHERE로 빌드 (#137).
     * 모든 인자는 ``?``로 바인딩 → SQL 인젝션 방지. sortBy/sortDir은 record의 컴팩트 생성자에서 화이트리스트 정규화됨.
     */
    private static final class DlqFilter {
        private final java.util.List<String> conditions = new java.util.ArrayList<>();
        private final java.util.List<Object> args = new java.util.ArrayList<>();
        DlqFilter(DlqQueryFilter f) {
            if (f.workflowName() != null && !f.workflowName().isBlank()) {
                conditions.add("WORKFLOW_NAME LIKE ?");
                args.add("%" + f.workflowName() + "%");
            }
            if (f.activityName() != null && !f.activityName().isBlank()) {
                conditions.add("ACTIVITY_NAME LIKE ?");
                args.add("%" + f.activityName() + "%");
            }
            if (f.errorMessage() != null && !f.errorMessage().isBlank()) {
                conditions.add("ERROR_MESSAGE LIKE ?");
                args.add("%" + f.errorMessage() + "%");
            }
            if (f.dlqStatusList() != null && !f.dlqStatusList().isEmpty()) {
                String placeholders = f.dlqStatusList().stream()
                        .map(s -> "?")
                        .collect(java.util.stream.Collectors.joining(", "));
                conditions.add("DLQ_STATUS_ST IN (" + placeholders + ")");
                args.addAll(f.dlqStatusList());
            }
            if (f.failedAtFrom() != null) {
                conditions.add("FAILED_AT_DT >= ?");
                args.add(java.sql.Timestamp.valueOf(f.failedAtFrom()));
            }
            if (f.failedAtTo() != null) {
                conditions.add("FAILED_AT_DT <= ?");
                args.add(java.sql.Timestamp.valueOf(f.failedAtTo()));
            }
            // #159 — ACL READ 가시성 필터: WORKFLOW_NAME IN (?, ?, ...)
            if (f.workflowNameAllowList() != null) {
                if (f.workflowNameAllowList().isEmpty()) {
                    conditions.add("1=0");  // 빈 set → 0행 보장
                } else {
                    String placeholders = f.workflowNameAllowList().stream()
                            .map(s -> "?")
                            .collect(java.util.stream.Collectors.joining(", "));
                    conditions.add("WORKFLOW_NAME IN (" + placeholders + ")");
                    args.addAll(f.workflowNameAllowList());
                }
            }
        }
        String where() {
            return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        }
        java.util.List<Object> args() { return args; }
    }

    @Override
    public Map<String, Long> countByStatus() {
        String sql = "SELECT DLQ_STATUS_ST, COUNT(*) FROM H_LINE_DLQ GROUP BY DLQ_STATUS_ST";
        Map<String, Long> out = new HashMap<>();
        jdbcTemplate.query(sql, rs -> { out.put(rs.getString(1), rs.getLong(2)); });
        return out;
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
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT") != null ? rs.getTimestamp("REG_DT").toLocalDateTime() : null,
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }
}
